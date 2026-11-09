package dev.meowy.client.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
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
 * whether the player's look vector is aimed at a visible, attackable, pickable entity
 * (supporting long-distance targeting when holding a bow/projectile weapon).
 */
public final class CrosshairProjectionHelper {
    private static final double MAX_RAYCAST_DISTANCE = 64.0;
    private static final double MAX_BOW_RAYCAST_DISTANCE = 128.0;

    private static float smoothedDx = 0.0f;
    private static float smoothedDy = 0.0f;

    private CrosshairProjectionHelper() {}

    /**
     * Checks if the local player is hovering over a visible, attackable, pickable entity.
     * Uses melee entityInteractionRange() normally, or full long-range raycast (128 blocks)
     * when holding a bow or projectile weapon in either hand.
     */
    public static boolean isHoveringTargetEntity(Minecraft client, float partialTicks) {
        if (client.player == null || client.level == null) {
            return false;
        }
        Entity directTarget = client.crosshairPickEntity;
        if (isValidTargetEntity(client.player, directTarget)) {
            return true;
        }

        double maxReach = isHoldingBowOrProjectileWeapon(client.player)
            ? MAX_BOW_RAYCAST_DISTANCE
            : client.player.entityInteractionRange();
        Entity raycastEntity = raycastEntity(client.player, partialTicks, maxReach);
        return isValidTargetEntity(client.player, raycastEntity);
    }

    public static boolean isHoldingBowOrProjectileWeapon(Player player) {
        ItemStack main = player.getMainHandItem();
        ItemStack off = player.getOffhandItem();
        return isBowOrProjectileWeapon(main) || isBowOrProjectileWeapon(off);
    }

    private static boolean isBowOrProjectileWeapon(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return stack.getItem() instanceof BowItem || stack.getItem() instanceof ProjectileWeaponItem;
    }

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
            return new Vector3f(0.0f, 0.0f, 1.0f);
        }

        Player player = client.player;
        Vec3 eyePos = player.getEyePosition(partialTicks);
        Vec3 lookVec = player.getViewVector(partialTicks);
        Vec3 endPos = eyePos.add(lookVec.scale(MAX_RAYCAST_DISTANCE));

        // 1. Block raycast from player's actual eye position
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

        // 2. Entity raycast along the same ray so entity hits take priority if closer
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

        // 3. Project targetWorldPos relative to the third-person camera into screen coordinates
        Camera camera = client.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        float relX = (float) (targetWorldPos.x - camPos.x);
        float relY = (float) (targetWorldPos.y - camPos.y);
        float relZ = (float) (targetWorldPos.z - camPos.z);

        // Try camera's view-rotation-projection matrix first
        try {
            Matrix4f viewRotProjMatrix = camera.getViewRotationProjectionMatrix(new Matrix4f());
            Vector4f clipSpace = new Vector4f(relX, relY, relZ, 1.0f).mul(viewRotProjMatrix);
            if (clipSpace.w > 0.05f) {
                float ndcX = clipSpace.x / clipSpace.w;
                float ndcY = clipSpace.y / clipSpace.w;
                float screenX = (ndcX * 0.5f + 0.5f) * guiWidth;
                float screenY = (0.5f - ndcY * 0.5f) * guiHeight;
                float rawDx = Math.clamp(screenX - (guiWidth * 0.5f), -guiWidth * 0.45f, guiWidth * 0.45f);
                float rawDy = Math.clamp(screenY - (guiHeight * 0.5f), -guiHeight * 0.45f, guiHeight * 0.45f);
                smoothedDx = smoothedDx + (rawDx - smoothedDx) * 0.55f;
                smoothedDy = smoothedDy + (rawDy - smoothedDy) * 0.55f;
                return new Vector3f(smoothedDx, smoothedDy, 1.0f);
            }
        } catch (Throwable ignored) {}

        // Fallback analytical projection using camera rotation quaternion and FOV
        Quaternionf invCamRot = new Quaternionf(camera.rotation()).conjugate();
        Vector3f viewSpace = new Vector3f(relX, relY, relZ).rotate(invCamRot);
        if (viewSpace.z >= -0.05f) {
            return new Vector3f(0.0f, 0.0f, -1.0f);
        }

        double fovDeg = client.options.fov().get();
        float tanHalfFov = (float) Math.tan(Math.toRadians(fovDeg * 0.5));
        float aspect = (float) guiWidth / (float) Math.max(1, guiHeight);

        float ndcX = (viewSpace.x / -viewSpace.z) / (tanHalfFov * aspect);
        float ndcY = (viewSpace.y / -viewSpace.z) / tanHalfFov;

        float rawDx = Math.clamp(ndcX * (guiWidth * 0.5f), -guiWidth * 0.45f, guiWidth * 0.45f);
        float rawDy = Math.clamp(-ndcY * (guiHeight * 0.5f), -guiHeight * 0.45f, guiHeight * 0.45f);
        smoothedDx = smoothedDx + (rawDx - smoothedDx) * 0.55f;
        smoothedDy = smoothedDy + (rawDy - smoothedDy) * 0.55f;
        return new Vector3f(smoothedDx, smoothedDy, 1.0f);
    }

    private static Entity raycastEntity(Player player, float partialTicks, double maxDistance) {
        Vec3 eyePos = player.getEyePosition(partialTicks);
        Vec3 lookVec = player.getViewVector(partialTicks);
        Vec3 endPos = eyePos.add(lookVec.scale(maxDistance));

        BlockHitResult blockHit = player.level().clip(new ClipContext(
            eyePos, endPos, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player
        ));
        double maxDistSq = blockHit.getType() != HitResult.Type.MISS
            ? eyePos.distanceToSqr(blockHit.getLocation())
            : maxDistance * maxDistance;

        AABB box = player.getBoundingBox().expandTowards(lookVec.scale(maxDistance)).inflate(1.0);
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
            player,
            eyePos,
            endPos,
            box,
            e -> isValidTargetEntity(player, e),
            maxDistSq
        );
        return entityHit != null ? entityHit.getEntity() : null;
    }
}
