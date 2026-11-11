package dev.ow.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.meowy.client.mixin.AbstractArrowAccessor;
import dev.meowy.client.render.CrosshairProjectionHelper;
import dev.ow.client.config.OwTuning;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hybrid Laser Tracer & Bolt Renderer combining the visual appearance of Instant Tracer
 * (long tapered tracer beam with a bright blazing head + camera-facing nose glow quad and a
 * smoothly fading trailing wake that extends cleanly from the muzzle) with the rock-solid
 * stability of Classic Bolt (attached directly to the real arrow entity in ArrowRenderer).
 *
 * <p>Includes client-side bullet termination on entity impact or bounce: as soon as an arrow hits
 * an entity, bounces/reverses direction, or loses flight velocity, it is permanently marked
 * terminated so neither the laser beam nor the dust trail ever bounces off an entity and falls to the ground.
 */
public final class LaserBulletRenderer {

    /**
     * Immutable per-frame extracted state for a single active local-player arrow projectile.
     */
    public record LaserBulletState(
        float dirX,
        float dirY,
        float dirZ,
        float tailWakeDist,
        float headBodyDist,
        float noseDist,
        float forwardOffset,
        float camToBoltX,
        float camToBoltY,
        float camToBoltZ,
        float radiusScale,
        boolean inGround,
        int glowRgb,
        int coreRgb
    ) {}

    private static final class FlightRecord {
        final float initDirX;
        final float initDirY;
        final float initDirZ;
        float prevDirX;
        float prevDirY;
        float prevDirZ;
        float peakSpeed;
        Vec3 lastPos;
        int lastCheckedTick = -1;
        boolean touchedEntity;

        FlightRecord(float dx, float dy, float dz, float speed, Vec3 pos) {
            this.initDirX = dx;
            this.initDirY = dy;
            this.initDirZ = dz;
            this.prevDirX = dx;
            this.prevDirY = dy;
            this.prevDirZ = dz;
            this.peakSpeed = speed;
            this.lastPos = pos;
            this.touchedEntity = false;
        }
    }

    public static final Identifier LASER_TEXTURE_ID = Identifier.fromNamespaceAndPath(
        "ow",
        "textures/entity/laser_bullet_tint.png"
    );

    public static final float HEAD_HALF_LENGTH = 0.62f;
    public static final float TAIL_WAKE_LENGTH = 1.95f;
    public static final float CORE_RADIUS       = 0.022f;
    public static final float OUTER_GLOW_RADIUS = 0.088f;

    /** Precomputed glow colour for OwTuning.PROJECTILE_RGB (0x1FD8FF). */
    public static final int GLOW_RGB = OwTuning.PROJECTILE_RGB;
    /** Precomputed 85%-lightened core colour for OwTuning.PROJECTILE_RGB. */
    public static final int CORE_RGB = 0xDDF9FF;

    private static final int FULLBRIGHT_LIGHT = 15728880; // 0xF000F0
    private static final int NO_OVERLAY       = 655360;   // OverlayTexture.NO_OVERLAY
    private static final float INV_SQRT_2     = 0.70710678f;

    /** Arrows that have impacted an entity, bounced, or lost projectile speed; never drawn again. */
    private static final Set<Integer> TERMINATED_ARROWS = ConcurrentHashMap.newKeySet();
    private static final Map<Integer, FlightRecord> FLIGHT_RECORDS = new ConcurrentHashMap<>();

    private LaserBulletRenderer() {}

    public static void markTerminated(int arrowId) {
        TERMINATED_ARROWS.add(arrowId);
    }

    public static void cleanupCache(ClientLevel level) {
        if (level == null) {
            TERMINATED_ARROWS.clear();
            FLIGHT_RECORDS.clear();
            return;
        }
        if (TERMINATED_ARROWS.size() > 256) {
            TERMINATED_ARROWS.removeIf(id -> level.getEntity(id) == null);
        }
        if (FLIGHT_RECORDS.size() > 256) {
            FLIGHT_RECORDS.keySet().removeIf(id -> level.getEntity(id) == null);
        }
    }

    /**
     * Evaluates whether a local arrow has hit an entity, bounced off an entity/shield, or lost flight speed.
     * Once true, the arrow is permanently marked terminated so it disappears on impact like a real bullet.
     */
    public static boolean checkAndUpdateTermination(AbstractArrow arrow) {
        if (arrow == null) {
            return true;
        }
        int id = arrow.getId();
        if (TERMINATED_ARROWS.contains(id)) {
            return true;
        }
        if (((AbstractArrowAccessor) arrow).ow$isInGround() || !arrow.isAlive() || arrow.isRemoved()) {
            TERMINATED_ARROWS.add(id);
            return true;
        }

        Vec3 vel = arrow.getDeltaMovement();
        float speed = (float) vel.length();
        Vec3 curPos = arrow.position();

        // If the arrow has essentially stopped or is slowly dropping after tick 1, terminate it immediately
        if (arrow.tickCount > 1 && speed < 0.50f) {
            TERMINATED_ARROWS.add(id);
            return true;
        }

        if (speed > 1.0E-4f) {
            float dx = (float) (vel.x / speed);
            float dy = (float) (vel.y / speed);
            float dz = (float) (vel.z / speed);

            FlightRecord rec = FLIGHT_RECORDS.get(id);
            if (rec == null) {
                rec = new FlightRecord(dx, dy, dz, speed, curPos);
                FLIGHT_RECORDS.put(id, rec);
            } else {
                // If the arrow already touched a target entity on the previous check, terminate it now so it never bounces away
                if (rec.touchedEntity) {
                    TERMINATED_ARROWS.add(id);
                    return true;
                }

                // 1. Check sudden speed drop (vanilla entity bounce multiplies velocity by -0.1, a 90% speed drop)
                if (rec.peakSpeed > 0.75f && speed < rec.peakSpeed * 0.48f) {
                    TERMINATED_ARROWS.add(id);
                    return true;
                }

                // 2. Check sharp direction deflection or reversal (bounce off entity/shield/wall)
                float dotPrev = dx * rec.prevDirX + dy * rec.prevDirY + dz * rec.prevDirZ;
                float dotInit = dx * rec.initDirX + dy * rec.initDirY + dz * rec.initDirZ;
                float horizInitLenSq = (rec.initDirX * rec.initDirX + rec.initDirZ * rec.initDirZ);
                float horizCurLenSq = (dx * dx + dz * dz);
                if (horizInitLenSq > 0.04f && horizCurLenSq > 0.01f) {
                    float horizDot = (dx * rec.initDirX + dz * rec.initDirZ)
                        / (float) Math.sqrt(horizInitLenSq * horizCurLenSq);
                    if (horizDot < 0.35f) {
                        TERMINATED_ARROWS.add(id);
                        return true;
                    }
                }
                if (dotPrev < 0.35f || dotInit < 0.15f) {
                    TERMINATED_ARROWS.add(id);
                    return true;
                }

                rec.prevDirX = dx;
                rec.prevDirY = dy;
                rec.prevDirZ = dz;
                if (speed > rec.peakSpeed) {
                    rec.peakSpeed = speed;
                }
            }

            // 3. Check if the arrow has reached/intersected a target entity's hitbox (once per tick)
            Minecraft client = Minecraft.getInstance();
            if (rec.lastCheckedTick != arrow.tickCount && client != null && client.level != null && client.player != null) {
                rec.lastCheckedTick = arrow.tickCount;
                Vec3 prevPos = rec.lastPos != null ? rec.lastPos : curPos;
                Vec3 nextPos = curPos.add(vel.scale(0.35));
                for (Entity entity : client.level.entitiesForRendering()) {
                    if (CrosshairProjectionHelper.isValidTargetEntity(client.player, entity)) {
                        AABB box = entity.getBoundingBox().inflate(0.28);
                        if (box.contains(curPos) || box.clip(prevPos, nextPos).isPresent()) {
                            // Mark touchedEntity so this impact frame renders up to the target, and next frame/tick terminates!
                            rec.touchedEntity = true;
                            break;
                        }
                    }
                }
            }
            rec.lastPos = curPos;
        }

        return false;
    }

    public static LaserBulletState computeBulletState(AbstractArrow arrow, float xRotDeg, float yRotDeg, float partialTicks) {
        if (checkAndUpdateTermination(arrow)) {
            return null;
        }

        Vec3 velocity = arrow.getDeltaMovement();
        float dirX;
        float dirY;
        float dirZ;

        double lenSqr = velocity.lengthSqr();
        if (lenSqr > 1.0E-5) {
            double invLen = 1.0 / Math.sqrt(lenSqr);
            dirX = (float) (velocity.x * invLen);
            dirY = (float) (velocity.y * invLen);
            dirZ = (float) (velocity.z * invLen);
        } else {
            float yRotRad = yRotDeg * ((float) Math.PI / 180.0f);
            float xRotRad = xRotDeg * ((float) Math.PI / 180.0f);
            float cosX = Mth.cos(xRotRad);
            dirX = -Mth.sin(yRotRad) * cosX;
            dirY = -Mth.sin(xRotRad);
            dirZ = Mth.cos(yRotRad) * cosX;
            float norm = (float) Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
            if (norm > 1.0E-4f) {
                dirX /= norm;
                dirY /= norm;
                dirZ /= norm;
            } else {
                dirX = 0.0f;
                dirY = 0.0f;
                dirZ = 1.0f;
            }
        }

        Minecraft client = Minecraft.getInstance();
        boolean firstPerson = client == null || client.options == null || client.options.getCameraType().isFirstPerson();
        Vec3 arrowPos = arrow.getPosition(partialTicks).add(0.0, 0.25, 0.0);
        Vec3 camPos = (client != null && client.gameRenderer != null)
            ? client.gameRenderer.mainCamera().position()
            : arrowPos;

        float camToBoltX = (float) (arrowPos.x - camPos.x);
        float camToBoltY = (float) (arrowPos.y - camPos.y);
        float camToBoltZ = (float) (arrowPos.z - camPos.z);
        float distToCam = (float) Math.sqrt(camToBoltX * camToBoltX + camToBoltY * camToBoltY + camToBoltZ * camToBoltZ);

        float radiusScale = 1.0f;
        float lengthScale = 1.0f;
        float forwardOffset = 0.0f;
        float distFromEye = 8.0f;

        if (client != null && client.player != null) {
            Vec3 eyePos = client.player.getEyePosition(partialTicks);
            distFromEye = (float) arrowPos.distanceTo(eyePos);
        }

        if (!firstPerson) {
            radiusScale = Mth.clamp(
                Math.max(1.0f, distToCam / OwTuning.THIRD_PERSON_REF_DIST),
                1.0f,
                OwTuning.THIRD_PERSON_MAX_RADIUS_SCALE
            );
            lengthScale = Mth.clamp(
                1.0f + (radiusScale - 1.0f) * 0.55f,
                1.0f,
                OwTuning.THIRD_PERSON_MAX_LENGTH_SCALE
            );
            if (distFromEye < OwTuning.THIRD_PERSON_PLAYER_CLEARANCE) {
                forwardOffset = OwTuning.THIRD_PERSON_PLAYER_CLEARANCE - distFromEye;
            }
        }

        float noseDist = HEAD_HALF_LENGTH * lengthScale;
        float desiredHeadBody = HEAD_HALF_LENGTH * lengthScale;
        float desiredTailWake = (HEAD_HALF_LENGTH + TAIL_WAKE_LENGTH) * lengthScale;

        float maxBackDist = Math.max(0.35f, distFromEye + forwardOffset - 0.35f);
        float headBodyDist = Math.min(desiredHeadBody, maxBackDist);
        float tailWakeDist = Math.min(desiredTailWake, Math.max(headBodyDist + 0.15f, maxBackDist));

        int glow = GLOW_RGB;
        int core = CORE_RGB;
        return new LaserBulletState(
            dirX, dirY, dirZ,
            tailWakeDist, headBodyDist, noseDist, forwardOffset,
            camToBoltX, camToBoltY, camToBoltZ,
            radiusScale, false, glow, core
        );
    }

    public static void submitLaserBullet(
        LaserBulletState state,
        PoseStack poseStack,
        SubmitNodeCollector submitNodeCollector
    ) {
        if (state == null || state.inGround()) {
            return;
        }
        RenderType renderType = RenderTypes.entityTranslucent(LASER_TEXTURE_ID);
        poseStack.pushPose();
        poseStack.translate(
            state.dirX() * state.forwardOffset(),
            0.25 + state.dirY() * state.forwardOffset(),
            state.dirZ() * state.forwardOffset()
        );
        submitNodeCollector.submitCustomGeometry(
            poseStack,
            renderType,
            new LaserGeometry(state)
        );
        poseStack.popPose();
    }

    public static final class LaserGeometry implements SubmitNodeCollector.CustomGeometryRenderer {
        private final LaserBulletState state;

        public LaserGeometry(LaserBulletState state) {
            this.state = state;
        }

        @Override
        public void render(PoseStack.Pose pose, VertexConsumer buffer) {
            emitLaserBulletGeometry(pose, buffer, this.state);
        }
    }

    public static void emitLaserBulletGeometry(
        PoseStack.Pose pose,
        VertexConsumer buffer,
        LaserBulletState state
    ) {
        float dx = state.dirX();
        float dy = state.dirY();
        float dz = state.dirZ();

        float wakeTailX = -dx * state.tailWakeDist();
        float wakeTailY = -dy * state.tailWakeDist();
        float wakeTailZ = -dz * state.tailWakeDist();

        float midX = -dx * state.headBodyDist();
        float midY = -dy * state.headBodyDist();
        float midZ = -dz * state.headBodyDist();

        float noseX = dx * state.noseDist();
        float noseY = dy * state.noseDist();
        float noseZ = dz * state.noseDist();

        if (state.tailWakeDist() > state.headBodyDist() + 0.04f) {
            emitBeamSegment(
                pose,
                buffer,
                wakeTailX, wakeTailY, wakeTailZ,
                midX, midY, midZ,
                dx, dy, dz,
                state.camToBoltX(), state.camToBoltY(), state.camToBoltZ(),
                state.radiusScale() * 0.72f,
                state.glowRgb(), state.coreRgb(),
                135, 175,
                true,
                false
            );
        }

        emitBeamSegment(
            pose,
            buffer,
            midX, midY, midZ,
            noseX, noseY, noseZ,
            dx, dy, dz,
            state.camToBoltX(), state.camToBoltY(), state.camToBoltZ(),
            state.radiusScale(),
            state.glowRgb(), state.coreRgb(),
            245, 255,
            true,
            true
        );
    }

    public static void emitBeamSegment(
        PoseStack.Pose pose,
        VertexConsumer buffer,
        float tailX, float tailY, float tailZ,
        float noseX, float noseY, float noseZ,
        float dx, float dy, float dz,
        float camToMidX, float camToMidY, float camToMidZ,
        float radiusScale,
        int glowRgb, int coreRgb,
        int glowAlpha, int coreAlpha,
        boolean includeCrossedPlanes,
        boolean includeNoseGlow
    ) {
        float outerRadius = OUTER_GLOW_RADIUS * radiusScale;
        float coreRadius = CORE_RADIUS * radiusScale;

        float wx = dy * camToMidZ - dz * camToMidY;
        float wy = dz * camToMidX - dx * camToMidZ;
        float wz = dx * camToMidY - dy * camToMidX;
        float wLen = (float) Math.sqrt(wx * wx + wy * wy + wz * wz);

        float upX = 0.0f;
        float upY = 1.0f;
        float upZ = 0.0f;
        if (Math.abs(dy) > 0.95f) {
            upX = 1.0f;
            upY = 0.0f;
        }

        float ux = dy * upZ - dz * upY;
        float uy = dz * upX - dx * upZ;
        float uz = dx * upY - dy * upX;
        float uLen = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
        if (uLen > 1.0E-5f) {
            ux /= uLen;
            uy /= uLen;
            uz /= uLen;
        } else {
            ux = 1.0f;
            uy = 0.0f;
            uz = 0.0f;
        }

        if (wLen > 1.0E-5f) {
            wx /= wLen;
            wy /= wLen;
            wz /= wLen;
        } else {
            wx = ux;
            wy = uy;
            wz = uz;
        }

        emitLaserPlane(
            pose, buffer,
            tailX, tailY, tailZ,
            noseX, noseY, noseZ,
            wx, wy, wz,
            outerRadius, coreRadius,
            glowRgb, coreRgb,
            glowAlpha, coreAlpha
        );

        if (includeCrossedPlanes) {
            float vx = dy * uz - dz * uy;
            float vy = dz * ux - dx * uz;
            float vz = dx * uy - dy * ux;

            float d1x = (ux + vx) * INV_SQRT_2;
            float d1y = (uy + vy) * INV_SQRT_2;
            float d1z = (uz + vz) * INV_SQRT_2;

            float d2x = (vx - ux) * INV_SQRT_2;
            float d2y = (vy - uy) * INV_SQRT_2;
            float d2z = (vz - uz) * INV_SQRT_2;

            emitLaserPlane(pose, buffer, tailX, tailY, tailZ, noseX, noseY, noseZ, ux, uy, uz, outerRadius, coreRadius, glowRgb, coreRgb, glowAlpha, coreAlpha);
            emitLaserPlane(pose, buffer, tailX, tailY, tailZ, noseX, noseY, noseZ, vx, vy, vz, outerRadius, coreRadius, glowRgb, coreRgb, glowAlpha, coreAlpha);
            emitLaserPlane(pose, buffer, tailX, tailY, tailZ, noseX, noseY, noseZ, d1x, d1y, d1z, outerRadius, coreRadius, glowRgb, coreRgb, glowAlpha, coreAlpha);
            emitLaserPlane(pose, buffer, tailX, tailY, tailZ, noseX, noseY, noseZ, d2x, d2y, d2z, outerRadius, coreRadius, glowRgb, coreRgb, glowAlpha, coreAlpha);
        }

        if (includeNoseGlow) {
            emitCameraFacingNoseGlow(
                pose, buffer,
                noseX, noseY, noseZ,
                camToMidX, camToMidY, camToMidZ,
                wx, wy, wz,
                OwTuning.NOSE_GLOW_BASE_SIZE * radiusScale,
                glowRgb, coreRgb,
                glowAlpha, coreAlpha
            );
        }
    }

    private static void emitCameraFacingNoseGlow(
        PoseStack.Pose pose,
        VertexConsumer buffer,
        float cx, float cy, float cz,
        float camToPosX, float camToPosY, float camToPosZ,
        float rightX, float rightY, float rightZ,
        float halfSize,
        int glowRgb, int coreRgb,
        int glowAlpha, int coreAlpha
    ) {
        float cLen = (float) Math.sqrt(camToPosX * camToPosX + camToPosY * camToPosY + camToPosZ * camToPosZ);
        float fx = cLen > 1.0E-4f ? (camToPosX / cLen) : 0.0f;
        float fy = cLen > 1.0E-4f ? (camToPosY / cLen) : 0.0f;
        float fz = cLen > 1.0E-4f ? (camToPosZ / cLen) : 1.0f;

        float bx = fy * rightZ - fz * rightY;
        float by = fz * rightX - fx * rightZ;
        float bz = fx * rightY - fy * rightX;
        float bLen = (float) Math.sqrt(bx * bx + by * by + bz * bz);
        if (bLen > 1.0E-5f) {
            bx /= bLen;
            by /= bLen;
            bz /= bLen;
        } else {
            bx = 0.0f;
            by = 1.0f;
            bz = 0.0f;
        }

        emitBillboardQuad(
            pose, buffer,
            cx, cy, cz,
            rightX * halfSize, rightY * halfSize, rightZ * halfSize,
            bx * halfSize, by * halfSize, bz * halfSize,
            0.0f, 1.0f,
            (glowRgb >> 16) & 0xFF, (glowRgb >> 8) & 0xFF, glowRgb & 0xFF,
            Math.clamp(Math.round(glowAlpha * 0.90f), 0, 255)
        );

        float coreSize = halfSize * 0.44f;
        emitBillboardQuad(
            pose, buffer,
            cx, cy, cz,
            rightX * coreSize, rightY * coreSize, rightZ * coreSize,
            bx * coreSize, by * coreSize, bz * coreSize,
            0.35f, 0.65f,
            (coreRgb >> 16) & 0xFF, (coreRgb >> 8) & 0xFF, coreRgb & 0xFF,
            coreAlpha
        );
    }

    private static void emitBillboardQuad(
        PoseStack.Pose pose,
        VertexConsumer buffer,
        float cx, float cy, float cz,
        float rx, float ry, float rz,
        float ux, float uy, float uz,
        float uMin, float uMax,
        int r, int g, int b, int a
    ) {
        if (a <= 2) return;
        float x00 = cx - rx - ux, y00 = cy - ry - uy, z00 = cz - rz - uz;
        float x10 = cx + rx - ux, y10 = cy + ry - uy, z10 = cz + rz - uz;
        float x11 = cx + rx + ux, y11 = cy + ry + uy, z11 = cz + rz + uz;
        float x01 = cx - rx + ux, y01 = cy - ry + uy, z01 = cz - rz + uz;

        addLaserVertex(pose, buffer, x00, y00, z00, r, g, b, a, uMin, 0.0f);
        addLaserVertex(pose, buffer, x10, y10, z10, r, g, b, a, uMax, 0.0f);
        addLaserVertex(pose, buffer, x11, y11, z11, r, g, b, a, uMax, 1.0f);
        addLaserVertex(pose, buffer, x01, y01, z01, r, g, b, a, uMin, 1.0f);

        addLaserVertex(pose, buffer, x01, y01, z01, r, g, b, a, uMin, 1.0f);
        addLaserVertex(pose, buffer, x11, y11, z11, r, g, b, a, uMax, 1.0f);
        addLaserVertex(pose, buffer, x10, y10, z10, r, g, b, a, uMax, 0.0f);
        addLaserVertex(pose, buffer, x00, y00, z00, r, g, b, a, uMin, 0.0f);
    }

    private static void emitLaserPlane(
        PoseStack.Pose pose,
        VertexConsumer buffer,
        float tailX, float tailY, float tailZ,
        float noseX, float noseY, float noseZ,
        float rx, float ry, float rz,
        float outerRadius, float coreRadius,
        int glowRgb, int coreRgb,
        int glowAlpha, int coreAlpha
    ) {
        if (glowAlpha > 2) {
            emitDoubleSidedQuad(
                pose, buffer,
                tailX, tailY, tailZ,
                noseX, noseY, noseZ,
                rx * outerRadius, ry * outerRadius, rz * outerRadius,
                0.0f, 1.0f,
                (glowRgb >> 16) & 0xFF, (glowRgb >> 8) & 0xFF, glowRgb & 0xFF, glowAlpha
            );
        }
        if (coreAlpha > 2) {
            emitDoubleSidedQuad(
                pose, buffer,
                tailX, tailY, tailZ,
                noseX, noseY, noseZ,
                rx * coreRadius, ry * coreRadius, rz * coreRadius,
                0.35f, 0.65f,
                (coreRgb >> 16) & 0xFF, (coreRgb >> 8) & 0xFF, coreRgb & 0xFF, coreAlpha
            );
        }
    }

    private static void emitDoubleSidedQuad(
        PoseStack.Pose pose,
        VertexConsumer buffer,
        float x0, float y0, float z0,
        float x1, float y1, float z1,
        float ox, float oy, float oz,
        float uMin, float uMax,
        int r, int g, int b, int a
    ) {
        float ax0 = x0 - ox, ay0 = y0 - oy, az0 = z0 - oz;
        float bx0 = x0 + ox, by0 = y0 + oy, bz0 = z0 + oz;
        float bx1 = x1 + ox, by1 = y1 + oy, bz1 = z1 + oz;
        float ax1 = x1 - ox, ay1 = y1 - oy, az1 = z1 - oz;

        addLaserVertex(pose, buffer, ax0, ay0, az0, r, g, b, a, uMin, 0.0f);
        addLaserVertex(pose, buffer, bx0, by0, bz0, r, g, b, a, uMax, 0.0f);
        addLaserVertex(pose, buffer, bx1, by1, bz1, r, g, b, a, uMax, 1.0f);
        addLaserVertex(pose, buffer, ax1, ay1, az1, r, g, b, a, uMin, 1.0f);

        addLaserVertex(pose, buffer, ax1, ay1, az1, r, g, b, a, uMin, 1.0f);
        addLaserVertex(pose, buffer, bx1, by1, bz1, r, g, b, a, uMax, 1.0f);
        addLaserVertex(pose, buffer, bx0, by0, bz0, r, g, b, a, uMax, 0.0f);
        addLaserVertex(pose, buffer, ax0, ay0, az0, r, g, b, a, uMin, 0.0f);
    }

    private static void addLaserVertex(
        PoseStack.Pose pose,
        VertexConsumer buffer,
        float x, float y, float z,
        int r, int g, int b, int a,
        float u, float v
    ) {
        buffer.addVertex(pose, x, y, z)
            .setColor(r, g, b, a)
            .setUv(u, v)
            .setOverlay(NO_OVERLAY)
            .setLight(FULLBRIGHT_LIGHT)
            .setNormal(pose, 0.0f, 1.0f, 0.0f);
    }
}
