package dev.meowy.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.meowy.client.MeowyClient;
import dev.ow.client.config.OwConfig;
import dev.ow.client.projectile.LocalArrowTracker;
import dev.ow.client.render.LaserBulletRenderer;
import dev.ow.client.render.OwArrowRenderStateExt;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ArrowRenderer;
import net.minecraft.client.renderer.entity.state.ArrowRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides the 3D arrow model strictly for arrows fired by the local player and submits the laser bullet
 * replacement using the unified hybrid laser tracer renderer.
 */
@Mixin(ArrowRenderer.class)
public abstract class ArrowRendererMixin<T extends AbstractArrow, S extends ArrowRenderState> {

    @Inject(
        method = "extractRenderState(Lnet/minecraft/world/entity/projectile/arrow/AbstractArrow;Lnet/minecraft/client/renderer/entity/state/ArrowRenderState;F)V",
        at = @At("RETURN")
    )
    private void ow$markLocalPlayerArrowState(T entity, S state, float partialTicks, CallbackInfo ci) {
        OwArrowRenderStateExt ext = (OwArrowRenderStateExt) state;
        try {
            boolean replaceWithLaser = OwConfig.get().laserBulletEnabled && LocalArrowTracker.isLocalPlayerArrow(entity);
            ext.ow$setLocalPlayerArrow(replaceWithLaser);
            if (replaceWithLaser) {
                state.shadowRadius = 0.0f;
                ext.ow$setBulletState(LaserBulletRenderer.computeBulletState(entity, state.xRot, state.yRot, partialTicks));
            } else {
                ext.ow$setBulletState(null);
            }
        } catch (Throwable t) {
            ext.ow$setLocalPlayerArrow(false);
            ext.ow$setBulletState(null);
            MeowyClient.logOnce("ArrowRendererMixin.extract", t);
        }
    }

    @Inject(
        method = "submit(Lnet/minecraft/client/renderer/entity/state/ArrowRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void ow$submitLaserBulletAndHideArrowModel(
        S state,
        PoseStack poseStack,
        SubmitNodeCollector submitNodeCollector,
        CameraRenderState camera,
        CallbackInfo ci
    ) {
        OwArrowRenderStateExt ext = (OwArrowRenderStateExt) state;
        if (ext.ow$isLocalPlayerArrow()) {
            try {
                LaserBulletRenderer.LaserBulletState bulletState = ext.ow$getBulletState();
                if (bulletState != null) {
                    LaserBulletRenderer.submitLaserBullet(bulletState, poseStack, submitNodeCollector);
                }
                // Cancel the vanilla arrow model when replaced with the hybrid laser tracer
                ci.cancel();
            } catch (Throwable t) {
                MeowyClient.logOnce("ArrowRendererMixin.submit", t);
            }
        }
    }
}
