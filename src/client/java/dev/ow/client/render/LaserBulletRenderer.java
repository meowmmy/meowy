package dev.ow.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.meowy.client.MeowyClient;
import dev.ow.client.config.ColorUtil;
import dev.ow.client.config.OwTuning;
import dev.meowy.client.mixin.AbstractArrowAccessor;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.Vec3;

/**
 * Replaces the local player's invisible 3D arrow model with a compact, bright laser bullet matching
 * {@code Capture.PNG}. The bolt colour comes from the {@code Projectile Color} setting (default: Pulse Pistol
 * cyan-blue); it is re-read every frame, so changing it in the GUI applies instantly to arrows already in flight.
 *
 * <p>Uses Minecraft 26.2's {@link SubmitNodeCollector#submitCustomGeometry} with
 * {@link RenderTypes#entityTranslucent(Identifier)} so it works seamlessly with both vanilla
 * Minecraft 26.2 and Sodium 0.9.2+mc26.2 while respecting solid-block depth occlusion and
 * fullbright {@code 0xF000F0} emissive lighting.
 */
public final class LaserBulletRenderer {

    /**
     * Immutable per-frame extracted state for a single active local-player arrow projectile.
     */
    public record LaserBulletState(
        float dirX,
        float dirY,
        float dirZ,
        boolean inGround,
        int glowRgb,
        int coreRgb
    ) {}

    public static final Identifier LASER_TEXTURE_ID = Identifier.fromNamespaceAndPath(
        "ow",
        "textures/entity/laser_bullet_tint.png" // white-RGB copy of laser_bullet.png so vertex colour is exact
    );

    public static final float TOTAL_HALF_LENGTH = 0.56f; // 1.12 blocks total length
    public static final float CORE_RADIUS       = 0.022f; // Bright white-gold core half-width
    public static final float OUTER_GLOW_RADIUS = 0.088f; // Smooth golden outer glow half-width

    private static final int FULLBRIGHT_LIGHT = 15728880; // LightCoordsUtil.FULL_BRIGHT (0xF000F0)
    private static final int NO_OVERLAY       = 655360;   // OverlayTexture.NO_OVERLAY (0 | (10 << 16))
    private static final float INV_SQRT_2     = 0.70710678f;

    private LaserBulletRenderer() {}

    public static void init() {
        // Texture is loaded automatically by Minecraft's TextureManager from assets/ow/textures/entity/laser_bullet.png
    }

    public static LaserBulletState computeBulletState(AbstractArrow arrow, float xRotDeg, float yRotDeg) {
        boolean inGround = ((AbstractArrowAccessor) arrow).ow$isInGround() || !arrow.isAlive() || arrow.isRemoved();
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

        int glow = ColorUtil.renderGlowColor(OwTuning.PROJECTILE_RGB);
        int core = ColorUtil.lightenToward(glow, 0.85f);
        return new LaserBulletState(dirX, dirY, dirZ, inGround, glow, core);
    }

    /**
     * Submits the 3D golden-white laser bullet geometry for an active local-player arrow inside
     * {@code ArrowRenderer#submit}.
     */
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
        poseStack.translate(0.0, 0.25, 0.0);
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

    /**
     * Emits 4 intersecting radial planes (0, 45, 90, and 135 degrees around the arrow's velocity axis),
     * each containing both an outer golden glow quad and an inner blazing white-gold core quad
     * (16 double-sided quads = 64 vertices total per projectile).
     */
    public static void emitLaserBulletGeometry(
        PoseStack.Pose pose,
        VertexConsumer buffer,
        LaserBulletState state
    ) {
        float dx = state.dirX();
        float dy = state.dirY();
        float dz = state.dirZ();

        float upX = 0.0f;
        float upY = 1.0f;
        float upZ = 0.0f;
        if (Math.abs(dy) > 0.95f) {
            upX = 1.0f;
            upY = 0.0f;
        }

        // U = normalize(D x up)
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

        // V = normalize(D x U)
        float vx = dy * uz - dz * uy;
        float vy = dz * ux - dx * uz;
        float vz = dx * uy - dy * ux;

        float d1x = (ux + vx) * INV_SQRT_2;
        float d1y = (uy + vy) * INV_SQRT_2;
        float d1z = (uz + vz) * INV_SQRT_2;

        float d2x = (vx - ux) * INV_SQRT_2;
        float d2y = (vy - uy) * INV_SQRT_2;
        float d2z = (vz - uz) * INV_SQRT_2;

        int glow = state.glowRgb();
        int core = state.coreRgb();
        emitLaserPlane(pose, buffer, dx, dy, dz, ux, uy, uz, glow, core);
        emitLaserPlane(pose, buffer, dx, dy, dz, vx, vy, vz, glow, core);
        emitLaserPlane(pose, buffer, dx, dy, dz, d1x, d1y, d1z, glow, core);
        emitLaserPlane(pose, buffer, dx, dy, dz, d2x, d2y, d2z, glow, core);
    }

    private static void emitLaserPlane(
        PoseStack.Pose pose,
        VertexConsumer buffer,
        float dx, float dy, float dz,
        float rx, float ry, float rz,
        int glowRgb, int coreRgb
    ) {
        float tailX = -dx * TOTAL_HALF_LENGTH;
        float tailY = -dy * TOTAL_HALF_LENGTH;
        float tailZ = -dz * TOTAL_HALF_LENGTH;

        float noseX = dx * TOTAL_HALF_LENGTH;
        float noseY = dy * TOTAL_HALF_LENGTH;
        float noseZ = dz * TOTAL_HALF_LENGTH;

        // 1. Outer glow ribbon (uses full radial gradient UV 0.0..1.0 across width and length)
        emitDoubleSidedQuad(
            pose, buffer,
            tailX, tailY, tailZ,
            noseX, noseY, noseZ,
            rx * OUTER_GLOW_RADIUS, ry * OUTER_GLOW_RADIUS, rz * OUTER_GLOW_RADIUS,
            0.0f, 1.0f,
            (glowRgb >> 16) & 0xFF, (glowRgb >> 8) & 0xFF, glowRgb & 0xFF, 245
        );

        // 2. Inner blazing near-white core ribbon (samples center core UV 0.35..0.65)
        emitDoubleSidedQuad(
            pose, buffer,
            tailX, tailY, tailZ,
            noseX, noseY, noseZ,
            rx * CORE_RADIUS, ry * CORE_RADIUS, rz * CORE_RADIUS,
            0.35f, 0.65f,
            (coreRgb >> 16) & 0xFF, (coreRgb >> 8) & 0xFF, coreRgb & 0xFF, 255
        );
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
        float ax0 = x0 - ox;
        float ay0 = y0 - oy;
        float az0 = z0 - oz;

        float bx0 = x0 + ox;
        float by0 = y0 + oy;
        float bz0 = z0 + oz;

        float bx1 = x1 + ox;
        float by1 = y1 + oy;
        float bz1 = z1 + oz;

        float ax1 = x1 - ox;
        float ay1 = y1 - oy;
        float az1 = z1 - oz;

        // Front face
        addLaserVertex(pose, buffer, ax0, ay0, az0, r, g, b, a, uMin, 0.0f);
        addLaserVertex(pose, buffer, bx0, by0, bz0, r, g, b, a, uMax, 0.0f);
        addLaserVertex(pose, buffer, bx1, by1, bz1, r, g, b, a, uMax, 1.0f);
        addLaserVertex(pose, buffer, ax1, ay1, az1, r, g, b, a, uMin, 1.0f);

        // Back face
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
