package dev.ow.client.animation;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ow.client.config.OwConfig;
import dev.ow.client.config.OwTuning;
import dev.ow.client.projectile.LocalArrowTracker;
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
 * Purely visual, client-side first-person animation for Cassidy's (McCree's) Peacemaker revolver.
 *
 * <p>This class is a completely independent controller: it has its own state machine, its own timing, its own
 * recoil spring and its own {@code CASSIDY_*} tuning constants in {@link OwTuning}. It shares no state or tuning with
 * {@link PistolAnimator} (Tracer) and only runs while the Peacemaker skin is the selected one.
 *
 * <h3>States</h3>
 * <ul>
 *   <li><b>IDLE</b>: no extra motion, the revolver sits exactly where the resource-pack display transform puts it.</li>
 *   <li><b>FIRING</b>: every shot adds an impulse to a heavy, damped spring that kicks the barrel up and the gun
 *       back toward the camera before it settles. Impulses simply add to the spring's current motion, so rapid
 *       clicking blends shot into shot (no restart, no snapping, no jitter).</li>
 *   <li><b>HANDLING</b>: once no shot has been fired and the fire button has not been held for the configured stop
 *       delay, the revolver plays Cassidy's automatic reload / handling sequence from the Overwatch reference clip:
 *       a small dip, the swing up with the wrist rolling the side and cylinder toward the camera, a second turn, an
 *       upright hold, a quick flick across the screen, a snap back upright, and the lower back to idle. It is a
 *       key-framed path (positions and rotations around the grip, not a spin around the model centre) sampled with
 *       cubic Hermite interpolation, so it is smooth and independent of frame rate.</li>
 * </ul>
 *
 * <h3>Shot detection</h3>
 * A shot is a real firing event, never just the state of the mouse button: either the bow-release edge of the
 * use-item action, or the spawn of the local player's projectile (see {@code LocalArrowTracker}). A release and
 * the arrow it produces are matched one-to-one, so rapid clicking registers every shot exactly once.
 * Holding the fire button only postpones the reload, it never triggers it.
 *
 * <h3>Guarantees</h3>
 * <ul>
 *   <li>Nothing here touches the server, inventory, cooldowns or projectiles: it only changes the
 *       {@link PoseStack} the held item is drawn with.</li>
 *   <li>All timing uses {@link System#nanoTime()}, so it is frame-rate independent.</li>
 *   <li>No per-frame allocation.</li>
 *   <li>State is wiped when the player object changes (respawn, dimension change, disconnect / reconnect), the
 *       player dies, or the revolver is no longer held / selected.</li>
 * </ul>
 */
public final class CassidyAnimator {

    private enum State { IDLE, FIRING, HANDLING }

    // Channel layout of the pose arrays (right-hand convention; mirrored for the left hand when applied).
    private static final int DX = 0;     // blocks, positive = toward the outside of the screen
    private static final int DY = 1;     // blocks, positive = up
    private static final int DZ = 2;     // blocks, positive = toward the camera
    private static final int PITCH = 3;  // degrees, positive = muzzle up
    private static final int YAW = 4;    // degrees, positive = muzzle toward the screen centre
    private static final int ROLL = 5;   // degrees, positive = top of the gun tips toward the screen centre
    private static final int CHANNELS = 6;

    /**
     * Pivot of every rotation: the grip (the "wrist"), expressed in the hand space the pose is applied in, i.e.
     * the grip point of the model run through the pack's first-person display transform. The X sign is mirrored
     * for the left hand.
     */
    private static final float PIVOT_X = 0.092f;
    private static final float PIVOT_Y = -0.0625f;
    private static final float PIVOT_Z = 0.151f;

    // ---- Handling (reload) path, right-hand convention, at animation speed 1.0 ----------------------------------
    // { time s, dx, dy, dz, pitch, yaw, roll }. Timing and poses follow the Overwatch clip: dip, swing up with the
    // wrist roll, second turn, hold, quick flick across, snap upright, lower with a tiny overshoot.
    private static final float[][] HANDLING_KEYS = {
        {0.000f,  0.000f, 0.000f,  0.000f,   0.0f,   0.0f,   0.0f},
        {0.112f,  0.000f, -0.040f, -0.060f,  -9.0f,   3.0f,   2.0f},
        {0.260f, -0.200f, 0.250f, -0.230f,  70.0f,  10.0f,   0.0f},
        {0.353f, -0.260f, 0.200f, -0.060f,  46.0f, -26.0f, -44.0f},
        {0.465f, -0.300f, 0.270f, -0.250f,  68.0f,   6.0f,  -8.0f},
        {0.577f, -0.300f, 0.230f, -0.080f,  42.0f,  48.0f,  50.0f},
        {0.688f, -0.280f, 0.250f, -0.240f,  70.0f,  14.0f,   6.0f},
        {0.874f, -0.275f, 0.255f, -0.240f,  74.0f,  12.0f,   4.0f},
        {0.986f, -0.250f, 0.110f, -0.100f,  16.0f,  62.0f,  26.0f},
        {1.060f, -0.270f, 0.170f, -0.120f,  50.0f,  36.0f,  40.0f},
        {1.153f, -0.250f, 0.200f, -0.200f,  68.0f,  14.0f,  10.0f},
        {1.358f, -0.100f, 0.060f, -0.060f,  24.0f,   4.0f,   0.0f},
        {1.469f,  0.000f, -0.008f, 0.000f,  -4.0f,   0.0f,   0.0f},
        {1.600f,  0.000f, 0.000f,  0.000f,   0.0f,   0.0f,   0.0f},
    };
    private static final int KEY_COUNT = HANDLING_KEYS.length;
    private static final float HANDLING_SECONDS = HANDLING_KEYS[KEY_COUNT - 1][0];
    /** Per-key tangents (Catmull-Rom on non-uniform times, zero at both ends so the path starts / ends at rest). */
    private static final float[][] HANDLING_TANGENTS = buildTangents();

    // ---- Recoil spring (heavier and slower than Tracer's: peak ~50 ms after a shot, settles in ~0.3 s) ----------
    private static final float SPRING_K = 480.0f;
    private static final float SPRING_C = 30.0f;
    private static final float SHOT_IMPULSE = 55.0f;   // gives a peak of ~1.0 for a single shot
    private static final float SPRING_MAX = 1.5f;      // rapid clicking stacks up to this, then saturates
    private static final float SPRING_MIN = -0.45f;
    private static final float SUBSTEP = 1.0f / 240.0f;
    private static final float RECOIL_PITCH_DEG = 8.0f;   // barrel kicks up (about the grip)
    private static final float RECOIL_BACK = 0.055f;      // gun jolts toward the camera
    private static final float RECOIL_RISE = 0.012f;
    private static final float RECOIL_ROLL_DEG = 2.2f;

    // ---- Shot detection --------------------------------------------------------------------------------------
    /** Projectiles spawned closer together than this belong to one volley. */
    private static final long VOLLEY_NS = 40_000_000L;
    /** Two shots closer together than this are one shot. */
    private static final long SHOT_DEDUPE_NS = 45_000_000L;
    /** How long a release-edge shot waits for the projectile it produces (server latency) before giving up. */
    private static final long ARROW_CLAIM_NS = 500_000_000L;
    /** Minimum draw time (ticks) for a release to count as a shot, so cancelling / switching is not a shot. */
    private static final int MIN_DRAW_TICKS = 2;
    private static final long NEVER = Long.MIN_VALUE / 4;

    // ---- Misc ------------------------------------------------------------------------------------------------
    /** Time constant for fading a cancelled handling pose back to idle when firing resumes. */
    private static final float BLEND_TAU_SECONDS = 0.10f;
    private static final float MAX_DT = 0.1f;
    private static final float EPS = 1.0E-4f;

    // ---- State -----------------------------------------------------------------------------------------------
    private static State state = State.IDLE;
    private static Object lastPlayer;
    private static boolean armed;
    private static long lastUpdateNs;
    private static long lastActivityNs = NEVER;
    private static long lastShotNs = NEVER;
    private static long lastVolleyNs = NEVER;
    private static long claimUntilNs = NEVER;
    private static long handlingStartNs;
    private static float handlingSpeed = 1.0f;
    private static int burstShots;
    private static boolean prevKeyDown;
    private static boolean prevUsing;
    private static int prevUseTicks;

    private static float springPos;
    private static float springVel;
    private static final float[] residual = new float[CHANNELS];

    // Scratch (guarded by the class lock).
    private static final float[] pose = new float[CHANNELS];
    private static final float[] handlingPose = new float[CHANNELS];
    private static final Quaternionf ROT = new Quaternionf();

    private CassidyAnimator() {}

    // =============================================================================================================
    // Public API
    // =============================================================================================================

    /**
     * Client tick hook: keeps state honest even while the revolver is not being rendered. With the laser bullet
     * switched off the normal arrow classification path never runs, so shots are polled for here instead.
     */
    public static void tick(Minecraft client) {
        boolean pollShots;
        synchronized (CassidyAnimator.class) {
            update(client, System.nanoTime());
            pollShots = armed && !OwConfig.get().laserBulletEnabled;
        }
        if (pollShots) {
            LocalArrowTracker.pollLocalShots(client);
        }
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
        lastActivityNs = NEVER;
        lastShotNs = NEVER;
        lastVolleyNs = NEVER;
        claimUntilNs = NEVER;
        springPos = 0.0f;
        springVel = 0.0f;
        java.util.Arrays.fill(residual, 0.0f);
    }

    /**
     * A projectile of the local player appeared (see {@code LocalArrowTracker}). Safe to call for every projectile
     * of a multi-projectile shot: they collapse into one shot. A projectile that belongs to a shot already
     * registered from the release edge is swallowed instead of counting twice.
     */
    public static synchronized void onLocalArrowShot() {
        long now = System.nanoTime();
        if (now - lastVolleyNs < VOLLEY_NS) {
            return; // sibling projectile of the volley already handled
        }
        lastVolleyNs = now;
        if (now < claimUntilNs) {
            claimUntilNs = NEVER; // this projectile is the one the release-edge shot was waiting for
            return;
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
        float side;
        if (context == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND) {
            side = 1.0f;
        } else if (context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND) {
            side = -1.0f;
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

        computePose(now, pose);
        float sum = 0.0f;
        for (int i = 0; i < CHANNELS; i++) {
            sum += Math.abs(pose[i]);
        }
        if (sum < EPS) {
            return false;
        }

        float px = side * PIVOT_X;
        poseStack.pushPose();
        poseStack.translate(side * pose[DX], pose[DY], pose[DZ]);
        poseStack.translate(px, PIVOT_Y, PIVOT_Z);
        poseStack.mulPose(ROT.rotationY((float) Math.toRadians(side * pose[YAW])));
        poseStack.mulPose(ROT.rotationX((float) Math.toRadians(pose[PITCH])));
        poseStack.mulPose(ROT.rotationZ((float) Math.toRadians(side * pose[ROLL])));
        poseStack.translate(-px, -PIVOT_Y, -PIVOT_Z);
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
            && cfg.getSelectedBowSkin() == BowSkin.MCCREE_PEACEKEEPER
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
        float speed = OwTuning.CASSIDY_ANIMATION_SPEED;

        boolean keyDown = client.options.keyUse.isDown() && ScreenAccess.current(client) == null;
        ItemStack useItem = player.isUsingItem() ? player.getUseItem() : ItemStack.EMPTY;
        boolean using = BowSkinManager.isBow(useItem, null);
        int useTicks = using ? player.getTicksUsingItem() : 0;

        // Bow release = a shot. Needs a minimum draw so cancelling / switching is not mistaken for one.
        if (prevUsing && !using && prevUseTicks >= MIN_DRAW_TICKS) {
            claimUntilNs = now + ARROW_CLAIM_NS;
            registerShot(now);
        }
        if (claimUntilNs != NEVER && now >= claimUntilNs) {
            claimUntilNs = NEVER;
        }
        if (keyDown) {
            lastActivityNs = now;
            if (!prevKeyDown && state == State.HANDLING) {
                cancelHandling(now);
                state = State.FIRING;
                burstShots = 0;
            }
        }
        prevKeyDown = keyDown;
        prevUsing = using;
        prevUseTicks = useTicks;

        advanceSpring(dt * speed);
        decayResidual(dt);

        long stopDelayNs = (long) OwTuning.CASSIDY_FIRING_STOP_DELAY_MS * 1_000_000L;
        switch (state) {
            case FIRING -> {
                if (now - lastActivityNs >= stopDelayNs) {
                    if (burstShots > 0 && OwTuning.CASSIDY_RELOAD_ANIMATION) {
                        state = State.HANDLING;
                        handlingStartNs = now;
                        handlingSpeed = speed;
                    } else {
                        state = State.IDLE;
                    }
                    burstShots = 0;
                }
            }
            case HANDLING -> {
                if (!OwTuning.CASSIDY_RELOAD_ANIMATION) {
                    cancelHandling(now); // switched off mid-animation: fade out instead of snapping
                    state = State.IDLE;
                } else if ((now - handlingStartNs) / 1.0E9f * handlingSpeed >= HANDLING_SECONDS) {
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
        if (state == State.HANDLING) {
            cancelHandling(now);
            burstShots = 0;
        }
        state = State.FIRING;
        burstShots++;
        if (OwTuning.CASSIDY_FIRING_ANIMATION) {
            springVel += SHOT_IMPULSE; // adds to whatever recoil is still playing: smooth stacking, no restart
        }
    }

    /** Captures the current handling pose so it fades out smoothly instead of snapping when firing resumes. */
    private static void cancelHandling(long now) {
        sampleHandling(now, handlingPose);
        for (int c = 0; c < CHANNELS; c++) {
            residual[c] += handlingPose[c];
        }
    }

    private static void advanceSpring(float dt) {
        if (dt <= 0.0f) {
            return;
        }
        float x = springPos;
        float v = springVel;
        if (Math.abs(x) < 1.0E-5f && Math.abs(v) < 1.0E-3f) {
            springPos = 0.0f;
            springVel = 0.0f;
            return;
        }
        int steps = Math.max(1, (int) Math.ceil(dt / SUBSTEP));
        float h = dt / steps;
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
        springPos = x;
        springVel = v;
    }

    private static void decayResidual(float dt) {
        if (dt <= 0.0f) {
            return;
        }
        float k = (float) Math.exp(-dt / BLEND_TAU_SECONDS);
        for (int i = 0; i < CHANNELS; i++) {
            float r = residual[i] * k;
            residual[i] = Math.abs(r) < 1.0E-4f ? 0.0f : r;
        }
    }

    // =============================================================================================================
    // Pose maths
    // =============================================================================================================

    /** recoil + handling path + faded residual, right-hand convention. */
    private static void computePose(long now, float[] out) {
        java.util.Arrays.fill(out, 0.0f);

        float r = OwTuning.CASSIDY_FIRING_ANIMATION ? springPos * OwTuning.CASSIDY_RECOIL_STRENGTH : 0.0f;
        if (r != 0.0f) {
            out[PITCH] += r * RECOIL_PITCH_DEG;
            out[DZ] += r * RECOIL_BACK;
            out[DY] += r * RECOIL_RISE;
            out[ROLL] += r * RECOIL_ROLL_DEG;
        }

        if (state == State.HANDLING) {
            sampleHandling(now, handlingPose);
            for (int c = 0; c < CHANNELS; c++) {
                out[c] += handlingPose[c];
            }
        }

        for (int c = 0; c < CHANNELS; c++) {
            out[c] += residual[c];
        }
    }

    /** The handling path at time {@code now}. Zero outside the animation window. */
    private static void sampleHandling(long now, float[] out) {
        float t = (now - handlingStartNs) / 1.0E9f * handlingSpeed;
        if (t <= 0.0f || t >= HANDLING_SECONDS) {
            java.util.Arrays.fill(out, 0.0f);
            return;
        }
        int i = 0;
        while (i < KEY_COUNT - 2 && t >= HANDLING_KEYS[i + 1][0]) {
            i++;
        }
        float[] a = HANDLING_KEYS[i];
        float[] b = HANDLING_KEYS[i + 1];
        float h = b[0] - a[0];
        float u = (t - a[0]) / h;
        float u2 = u * u;
        float u3 = u2 * u;
        float h00 = 2.0f * u3 - 3.0f * u2 + 1.0f;
        float h10 = u3 - 2.0f * u2 + u;
        float h01 = -2.0f * u3 + 3.0f * u2;
        float h11 = u3 - u2;
        for (int c = 0; c < CHANNELS; c++) {
            out[c] = h00 * a[c + 1] + h10 * h * HANDLING_TANGENTS[i][c]
                + h01 * b[c + 1] + h11 * h * HANDLING_TANGENTS[i + 1][c];
        }
    }

    private static float[][] buildTangents() {
        float[][] m = new float[KEY_COUNT][CHANNELS];
        for (int i = 1; i < KEY_COUNT - 1; i++) {
            float dtKey = HANDLING_KEYS[i + 1][0] - HANDLING_KEYS[i - 1][0];
            for (int c = 0; c < CHANNELS; c++) {
                m[i][c] = (HANDLING_KEYS[i + 1][c + 1] - HANDLING_KEYS[i - 1][c + 1]) / dtKey;
            }
        }
        return m;
    }
}
