package dev.ow.client.animation;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ow.client.config.OwConfig;
import dev.ow.client.config.OwTuning;
import dev.ow.client.skin.BowSkin;
import dev.ow.client.skin.BowSkinManager;
import dev.ow.client.util.ScreenAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;

/**
 * Purely visual, client-side first-person animation for Tracer's Pulse Pistols.
 *
 * <h3>States</h3>
 * <ul>
 *   <li><b>IDLE</b>: no extra motion, the pistols sit exactly where the resource-pack display transform puts them.</li>
 *   <li><b>FIRING</b>: every shot kicks a damped spring (per hand, the off hand a few ms later) that drives a quick
 *       recoil: muzzle flips up, pistol jolts toward the camera, then settles. Rapid fire just keeps re-kicking it.</li>
 *   <li><b>RECOVERY</b>: once firing has been quiet for the fixed stop delay ({@link OwTuning#TRACER_FIRING_STOP_DELAY_MS}), both pistols lift muzzle-up and
 *       slightly outward, spin a full flourish near the camera and settle back with a small overshoot (modelled on
 *       the Overwatch reference clip). The off hand trails the main hand by ~45 ms.</li>
 * </ul>
 *
 * <h3>Guarantees</h3>
 * <ul>
 *   <li>Nothing here touches the server, inventory, cooldowns or projectiles: it only changes the {@link PoseStack}
 *       the held item is drawn with.</li>
 *   <li>All timing uses {@link System#nanoTime()} and per-frame {@code dt}, so it is frame-rate independent.</li>
 *   <li>No per-frame allocation: scratch arrays / quaternions are reused.</li>
 *   <li>State is reset when the player object changes (respawn, dimension change, disconnect / reconnect), the
 *       player dies, or the pistol is no longer held.</li>
 * </ul>
 *
 * Everything is {@code synchronized}: the cost is negligible and it keeps shot notifications safe from any thread.
 */
public final class PistolAnimator {

    private enum State { IDLE, FIRING, RECOVERY }

    private static final int RIGHT = 0;
    private static final int LEFT = 1;

    // Channel layout of the pose arrays.
    private static final int DX = 0;
    private static final int DY = 1;
    private static final int DZ = 2;
    private static final int PITCH = 3;
    private static final int YAW = 4;
    private static final int ROLL = 5;
    private static final int CHANNELS = 6;

    // ---- Recovery flourish tuning (at animation speed 1.0) -------------------------------------------------
    private static final float RECOVERY_SECONDS = 0.80f;
    private static final float OFF_HAND_DELAY_SECONDS = 0.045f;
    private static final float LIFT_END = 0.24f;        // fraction of the animation spent lifting
    private static final float SETTLE_START = 0.56f;    // fraction at which the settle back down begins
    private static final float ROLL_START = 0.14f;
    private static final float ROLL_SPAN = 0.52f;
    private static final float LIFT_PITCH_DEG = 98.0f;  // muzzle up
    private static final float OUTWARD_YAW_DEG = 14.0f;
    private static final float FLIP_DEGREES = 360.0f;
    private static final float RISE = 0.07f;
    private static final float TOWARD_CAMERA = 0.10f;
    private static final float OUTWARD_SHIFT = 0.04f;
    private static final float SETTLE_OVERSHOOT = 0.9f;

    // ---- Recoil spring (critically-ish damped, peak ~36 ms after a shot) -----------------------------------
    private static final float SPRING_K = 1100.0f;
    private static final float SPRING_C = 33.0f;
    private static final float SHOT_IMPULSE = 68.0f;
    private static final float SPRING_MAX = 1.6f;
    private static final float SPRING_MIN = -0.5f;
    private static final float SUBSTEP = 1.0f / 240.0f;
    private static final float RECOIL_PITCH_DEG = 7.0f;
    private static final float RECOIL_ROLL_DEG = 2.5f;
    private static final float RECOIL_BACK = 0.05f;
    private static final float RECOIL_RISE = 0.012f;

    // ---- Misc ------------------------------------------------------------------------------------------------
    /** Shots closer together than this are one volley (multi-arrow shots). */
    private static final long SHOT_DEDUPE_NS = 40_000_000L;
    /** Arrow-spawn detections are ignored this long after a use-release shot (spawn packet lag on servers). */
    private static final long ARROW_AFTER_RELEASE_NS = 400_000_000L;
    private static final long OFF_HAND_KICK_DELAY_NS = 35_000_000L;
    /** Time constant for blending a cancelled recovery back into firing. */
    private static final float BLEND_TAU_SECONDS = 0.09f;
    private static final float MAX_DT = 0.1f;
    private static final float EPS = 1.0E-4f;

    // ---- State -----------------------------------------------------------------------------------------------
    private static State state = State.IDLE;
    private static Object lastPlayer;
    private static boolean armed;
    private static long lastUpdateNs;
    private static long lastActivityNs;
    private static long lastShotNs;
    private static long lastReleaseShotNs;
    private static long recoveryStartNs;
    private static float recoveryDuration;
    private static int burstShots;
    private static boolean prevKeyDown;
    private static boolean prevUsing;
    private static int prevUseTicks;

    private static final float[] springPos = new float[2];
    private static final float[] springVel = new float[2];
    private static final long[] pendingKickNs = new long[2];
    private static final boolean[] kickPending = new boolean[2];
    private static final float[] residual = new float[2 * CHANNELS];

    // Scratch (render thread only, guarded by the class lock).
    private static final float[] pose = new float[CHANNELS];
    private static final float[] recoveryPose = new float[CHANNELS];
    private static final Quaternionf ROT = new Quaternionf();

    private PistolAnimator() {}

    // =============================================================================================================
    // Public API
    // =============================================================================================================

    /** Client tick hook: keeps state honest even while the pistol is not being rendered. */
    public static synchronized void tick(Minecraft client) {
        update(client, System.nanoTime());
    }

    /** Wipes all animation state (world leave, item switch, death...). */
    public static synchronized void reset() {
        state = State.IDLE;
        armed = false;
        burstShots = 0;
        prevKeyDown = false;
        prevUsing = false;
        prevUseTicks = 0;
        lastUpdateNs = 0L;
        lastShotNs = 0L;
        lastReleaseShotNs = 0L;
        for (int i = 0; i < 2; i++) {
            springPos[i] = 0.0f;
            springVel[i] = 0.0f;
            kickPending[i] = false;
        }
        java.util.Arrays.fill(residual, 0.0f);
    }

    /**
     * A shot was detected from a spawned arrow (see {@code LocalArrowTracker}). Safe to call repeatedly for the
     * several arrows of one volley.
     */
    public static synchronized void onLocalArrowShot() {
        long now = System.nanoTime();
        if (now - lastReleaseShotNs < ARROW_AFTER_RELEASE_NS) {
            return; // already kicked by the release edge of this very shot
        }
        registerShot(now);
    }

    /**
     * Called from the first-person item renderer right before the held item is drawn. Pushes a transform on
     * {@code poseStack} and returns {@code true} when it did (the caller must then {@code popPose()}).
     */
    public static synchronized boolean applyFirstPerson(
        LivingEntity entity,
        ItemStack stack,
        ItemDisplayContext context,
        PoseStack poseStack
    ) {
        int hand;
        if (context == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND) {
            hand = RIGHT;
        } else if (context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND) {
            hand = LEFT;
        } else {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || entity != client.player) {
            return false;
        }
        if (!BowSkinManager.isBow(stack, null)) {
            return false;
        }

        long now = System.nanoTime();
        update(client, now);
        if (!armed) {
            return false;
        }

        computePose(hand, now, pose);
        float sum = 0.0f;
        for (int i = 0; i < CHANNELS; i++) {
            sum += Math.abs(pose[i]);
        }
        if (sum < EPS) {
            return false;
        }

        float side = hand == RIGHT ? 1.0f : -1.0f;
        // Pivot ~ centre of the pistol in pre-display space (the pack's firstperson translation is 3, 3.75, -2.5 px),
        // so pitch/roll spin the pistol in place instead of orbiting the hand origin.
        float px = side * 0.1875f;
        float py = 0.234f;
        float pz = -0.17f;

        poseStack.pushPose();
        poseStack.translate(pose[DX], pose[DY], pose[DZ]);
        poseStack.translate(px, py, pz);
        // Muzzle-outward yaw: right hand turns right (-Y), left hand turns left (+Y).
        poseStack.mulPose(ROT.rotationY((float) Math.toRadians(-side * pose[YAW])));
        poseStack.mulPose(ROT.rotationX((float) Math.toRadians(pose[PITCH])));
        poseStack.mulPose(ROT.rotationZ((float) Math.toRadians(pose[ROLL])));
        poseStack.translate(-px, -py, -pz);
        return true;
    }

    // =============================================================================================================
    // State machine
    // =============================================================================================================

    private static void update(Minecraft client, long now) {
        LocalPlayer player = client == null ? null : client.player;
        if (player == null) {
            lastPlayer = null;
            reset();
            return;
        }
        if (player != lastPlayer) {
            // New player object: respawn, dimension change, reconnect.
            lastPlayer = player;
            reset();
        }

        OwConfig cfg = OwConfig.get();
        boolean holding = player.isAlive()
            && cfg.isBowSkinEnabled()
            && cfg.getSelectedBowSkin() == BowSkin.TRACER_PULSE_PISTOL
            && (BowSkinManager.isBow(player.getMainHandItem(), null) || BowSkinManager.isBow(player.getOffhandItem(), null));
        if (!holding) {
            if (armed || state != State.IDLE) {
                reset();
            }
            return;
        }
        armed = true;

        float dt = lastUpdateNs == 0L ? 0.0f : Math.min((now - lastUpdateNs) / 1.0E9f, MAX_DT);
        lastUpdateNs = now;

        boolean keyDown = client.options.keyUse.isDown() && ScreenAccess.current(client) == null;
        ItemStack useItem = player.isUsingItem() ? player.getUseItem() : ItemStack.EMPTY;
        boolean using = BowSkinManager.isBow(useItem, null);
        int useTicks = using ? player.getTicksUsingItem() : 0;

        // Bow release = vanilla shot. Needs a minimum draw so cancelling / switching is not mistaken for a shot.
        if (prevUsing && !using && prevUseTicks >= 2) {
            lastReleaseShotNs = now;
            registerShot(now);
        }
        if (keyDown) {
            lastActivityNs = now;
            if (!prevKeyDown && state == State.RECOVERY) {
                cancelRecovery(now);
                state = State.FIRING;
                burstShots = 0;
            }
        }
        prevKeyDown = keyDown;
        prevUsing = using;
        prevUseTicks = useTicks;

        // Delayed off-hand kick.
        for (int h = 0; h < 2; h++) {
            if (kickPending[h] && now >= pendingKickNs[h]) {
                kickPending[h] = false;
                springVel[h] += SHOT_IMPULSE;
            }
        }

        advanceSpring(dt);
        decayResidual(dt);

        long stopDelayNs = (long) OwTuning.TRACER_FIRING_STOP_DELAY_MS * 1_000_000L;
        switch (state) {
            case FIRING -> {
                if (now - lastActivityNs >= stopDelayNs) {
                    if (burstShots > 0 && OwTuning.TRACER_POST_FIRE_ANIMATION) {
                        startRecovery(now);
                    } else {
                        state = State.IDLE;
                    }
                    burstShots = 0;
                }
            }
            case RECOVERY -> {
                float elapsed = (now - recoveryStartNs) / 1.0E9f;
                if (elapsed >= recoveryDuration * (1.0f + OFF_HAND_DELAY_SECONDS / RECOVERY_SECONDS)) {
                    state = State.IDLE;
                }
            }
            default -> { }
        }
    }

    private static void registerShot(long now) {
        if (!armed || now - lastShotNs < SHOT_DEDUPE_NS) {
            return;
        }
        lastShotNs = now;
        lastActivityNs = now;
        if (state == State.RECOVERY) {
            cancelRecovery(now);
            burstShots = 0;
        }
        state = State.FIRING;
        burstShots++;

        springVel[RIGHT] += SHOT_IMPULSE;
        pendingKickNs[LEFT] = now + OFF_HAND_KICK_DELAY_NS;
        kickPending[LEFT] = true;
    }

    private static void startRecovery(long now) {
        state = State.RECOVERY;
        recoveryStartNs = now;
        recoveryDuration = RECOVERY_SECONDS / OwTuning.TRACER_ANIMATION_SPEED;
    }

    /** Captures the current flourish pose so it fades out smoothly instead of snapping when firing resumes. */
    private static void cancelRecovery(long now) {
        for (int h = 0; h < 2; h++) {
            computeRecoveryPose(h, now, recoveryPose);
            for (int c = 0; c < CHANNELS; c++) {
                residual[h * CHANNELS + c] += recoveryPose[c];
            }
        }
    }

    private static void advanceSpring(float dt) {
        if (dt <= 0.0f) {
            return;
        }
        int steps = Math.max(1, (int) Math.ceil(dt / SUBSTEP));
        float h = dt / steps;
        for (int hand = 0; hand < 2; hand++) {
            float x = springPos[hand];
            float v = springVel[hand];
            if (Math.abs(x) < 1.0E-5f && Math.abs(v) < 1.0E-3f) {
                springPos[hand] = 0.0f;
                springVel[hand] = 0.0f;
                continue;
            }
            for (int i = 0; i < steps; i++) {
                v += (-SPRING_K * x - SPRING_C * v) * h;
                x += v * h;
            }
            if (x > SPRING_MAX) {
                x = SPRING_MAX;
                v = Math.min(v, 0.0f);
            } else if (x < SPRING_MIN) {
                x = SPRING_MIN;
                v = Math.max(v, 0.0f);
            }
            springPos[hand] = x;
            springVel[hand] = v;
        }
    }

    private static void decayResidual(float dt) {
        if (dt <= 0.0f) {
            return;
        }
        float k = (float) Math.exp(-dt / BLEND_TAU_SECONDS);
        for (int i = 0; i < residual.length; i++) {
            float r = residual[i] * k;
            residual[i] = Math.abs(r) < 1.0E-4f ? 0.0f : r;
        }
    }

    // =============================================================================================================
    // Pose maths
    // =============================================================================================================

    /** recoil + recovery flourish + blended residual for one hand. */
    private static void computePose(int hand, long now, float[] out) {
        java.util.Arrays.fill(out, 0.0f);

        float r = springPos[hand];
        if (r != 0.0f) {
            float side = hand == RIGHT ? 1.0f : -1.0f;
            out[PITCH] += r * RECOIL_PITCH_DEG;
            out[DZ] += r * RECOIL_BACK;
            out[DY] += r * RECOIL_RISE;
            out[ROLL] += side * r * RECOIL_ROLL_DEG;
        }

        if (state == State.RECOVERY) {
            computeRecoveryPose(hand, now, recoveryPose);
            for (int c = 0; c < CHANNELS; c++) {
                out[c] += recoveryPose[c];
            }
        }

        int base = hand * CHANNELS;
        for (int c = 0; c < CHANNELS; c++) {
            out[c] += residual[base + c];
        }
    }

    /** The flourish for one hand at time {@code now}. Zero outside the animation window. */
    private static void computeRecoveryPose(int hand, long now, float[] out) {
        java.util.Arrays.fill(out, 0.0f);
        if (recoveryDuration <= 0.0f) {
            return;
        }
        float side = hand == RIGHT ? 1.0f : -1.0f;
        float delay = hand == LEFT ? OFF_HAND_DELAY_SECONDS * (recoveryDuration / RECOVERY_SECONDS) : 0.0f;
        float u = ((now - recoveryStartNs) / 1.0E9f - delay) / recoveryDuration;
        if (u <= 0.0f || u >= 1.0f) {
            return;
        }

        // Lift up fast, hold through the flourish, drop back with a little overshoot.
        float lift = easeOutCubic(clamp01(u / LIFT_END));
        float settle = easeInOutBack(clamp01((u - SETTLE_START) / (1.0f - SETTLE_START)));
        float elev = lift - settle;                          // 0 -> 1 -> ~0 (slightly negative at the catch, slightly >1 during wind-up)
        float elevPos = Math.max(0.0f, elev);

        float roll = easeInOutSine(clamp01((u - ROLL_START) / ROLL_SPAN));
        float bump = (float) Math.sin(Math.PI * clamp01((u - 0.10f) / 0.62f)); // peaks mid-flourish

        out[PITCH] = LIFT_PITCH_DEG * elev;
        out[YAW] = OUTWARD_YAW_DEG * elevPos;
        out[ROLL] = side * FLIP_DEGREES * roll;
        out[DY] = RISE * elevPos;
        out[DZ] = TOWARD_CAMERA * bump;
        out[DX] = side * OUTWARD_SHIFT * elevPos;
    }

    private static float clamp01(float v) {
        return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
    }

    private static float easeOutCubic(float x) {
        float p = 1.0f - x;
        return 1.0f - p * p * p;
    }

    private static float easeInOutSine(float x) {
        return -(float) (Math.cos(Math.PI * x) - 1.0) * 0.5f;
    }

    /** Small wind-up before the drop and a small overshoot at the catch. */
    private static float easeInOutBack(float x) {
        float c2 = SETTLE_OVERSHOOT * 1.525f;
        if (x < 0.5f) {
            float p = 2.0f * x;
            return (p * p * ((c2 + 1.0f) * p - c2)) * 0.5f;
        }
        float p = 2.0f * x - 2.0f;
        return (p * p * ((c2 + 1.0f) * p + c2) + 2.0f) * 0.5f;
    }
}
