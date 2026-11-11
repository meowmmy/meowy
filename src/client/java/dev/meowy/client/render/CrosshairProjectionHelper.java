package dev.meowy.client.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Computes accurate 3D-to-2D screen projections for the Third-Person crosshair and evaluates
 * whether an entity is a valid attackable target.
 */
public final class CrosshairProjectionHelper {
    private static final double MAX_RAYCAST_DISTANCE = 64.0;

    private static float smoothedDx = 0.0f;
    private static float smoothedDy = 0.0f;

    // Reusable scratch math objects (render thread only)
    private static final Matrix4f SCRATCH_MAT = new Matrix4f();
    private static final Vector4f SCRATCH_VEC4 = new Vector4f();
    private static final Vector3f SCRATCH_VEC3 = new Vector3f();
    private static final Vector3f RESULT_VEC3 = new Vector3f();
    private static final Quaternionf SCRATCH_QUAT = new Quaternionf();

    private CrosshairProjectionHelper() {}

    public static boolean isValidTargetEntity(Player localPlayer, Entity entity) {
        if (entity == null || entity == localPlayer || !entity.isAlive()) {
            return false;
        }
        if (!entity.isPickable() || !entity.isAttackable()) {
            return false;
        }
        return !entity.isInvisibleTo(localPlayer);
    }

    /**
     * Computes the GUI screen-space offset (dx, dy) from the center of the screen
     * for the Accurate Third-Person Crosshair by raycasting from the player's eyes and projecting
     * the 3D world hit point onto the third-person camera's screen plane.
     */
    public static Vector3f computeAccurateF5Offset(Minecraft client, float partialTicks, int guiWidth, int guiHeight) {
        if (client.player == null || client.level == null || client.gameRenderer == null) {
            return RESULT_VEC3.set(0.0f, 0.0f, 1.0f);
        }

        Player player = client.player;
        Vec3 eyePos = player.getEyePosition(partialTicks);
        Vec3 lookVec = player.getViewVector(partialTicks);
        Vec3 endPos = eyePos.add(lookVec.scale(MAX_RAYCAST_DISTANCE));

        BlockHitResult blockHit = client.level.clip(new ClipContext(
            eyePos,
            endPos,
            ClipContext.Block.OUTLINE,
            ClipContext.Fluid.NONE,
            player
        ));

        double closestDistSq = blockHit.getType() != HitResult.Type.MISS
            ? eyePos.distanceToSqr(blockHit.getLocation())
            : MAX_RAYCAST_DISTANCE * MAX_RAYCAST_DISTANCE;
        Vec3 targetWorldPos = blockHit.getType() != HitResult.Type.MISS
            ? blockHit.getLocation()
            : endPos;

        AABB searchBox = player.getBoundingBox().expandTowards(lookVec.scale(MAX_RAYCAST_DISTANCE)).inflate(1.0);
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
            player,
            eyePos,
            endPos,
            searchBox,
            e -> isValidTargetEntity(player, e),
            closestDistSq
        );
        if (entityHit != null) {
            targetWorldPos = entityHit.getLocation();
        }

        Camera camera = client.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        float relX = (float) (targetWorldPos.x - camPos.x);
        float relY = (float) (targetWorldPos.y - camPos.y);
        float relZ = (float) (targetWorldPos.z - camPos.z);

        try {
            Matrix4f viewRotProjMatrix = camera.getViewRotationProjectionMatrix(SCRATCH_MAT);
            Vector4f clipSpace = SCRATCH_VEC4.set(relX, relY, relZ, 1.0f).mul(viewRotProjMatrix);
            if (clipSpace.w > 0.05f) {
                float ndcX = clipSpace.x / clipSpace.w;
                float ndcY = clipSpace.y / clipSpace.w;
                float screenX = (ndcX * 0.5f + 0.5f) * guiWidth;
                float screenY = (0.5f - ndcY * 0.5f) * guiHeight;
                float rawDx = Math.clamp(screenX - (guiWidth * 0.5f), -guiWidth * 0.45f, guiWidth * 0.45f);
                float rawDy = Math.clamp(screenY - (guiHeight * 0.5f), -guiHeight * 0.45f, guiHeight * 0.45f);
                smoothedDx += (rawDx - smoothedDx) * 0.55f;
                smoothedDy += (rawDy - smoothedDy) * 0.55f;
                return RESULT_VEC3.set(smoothedDx, smoothedDy, 1.0f);
            }
        } catch (Throwable ignored) {}

        Quaternionf invCamRot = SCRATCH_QUAT.set(camera.rotation()).conjugate();
        Vector3f viewSpace = SCRATCH_VEC3.set(relX, relY, relZ).rotate(invCamRot);
        if (viewSpace.z >= -0.05f) {
            return RESULT_VEC3.set(0.0f, 0.0f, -1.0f);
        }

        double fovDeg = client.options.fov().get();
        float tanHalfFov = (float) Math.tan(Math.toRadians(fovDeg * 0.5));
        float aspect = (float) guiWidth / (float) Math.max(1, guiHeight);

        float ndcX = (viewSpace.x / -viewSpace.z) / (tanHalfFov * aspect);
        float ndcY = (viewSpace.y / -viewSpace.z) / tanHalfFov;

        float rawDx = Math.clamp(ndcX * (guiWidth * 0.5f), -guiWidth * 0.45f, guiWidth * 0.45f);
        float rawDy = Math.clamp(-ndcY * (guiHeight * 0.5f), -guiHeight * 0.45f, guiHeight * 0.45f);
        smoothedDx += (rawDx - smoothedDx) * 0.55f;
        smoothedDy += (rawDy - smoothedDy) * 0.55f;
        return RESULT_VEC3.set(smoothedDx, smoothedDy, 1.0f);
    }
}
