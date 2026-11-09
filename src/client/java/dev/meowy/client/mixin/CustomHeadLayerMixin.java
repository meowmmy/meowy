package dev.meowy.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.meowy.client.config.MeowyConfig;
import dev.meowy.client.render.MeowyRenderStateExt;
import dev.meowy.client.render.RenderOpacityContext;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies Helmet Opacity to head blocks (carved pumpkins, mob/player skulls, banners) on the local player.
 */
@Mixin(CustomHeadLayer.class)
public class CustomHeadLayerMixin<S extends LivingEntityRenderState> {

    @Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;FF)V", at = @At("HEAD"), cancellable = true)
    private void meowy$beforeCustomHeadSubmit(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, S state, float yRot, float xRot, CallbackInfo ci) {
        if (((MeowyRenderStateExt) state).meowy$isLocalPlayer()) {
            float opacity = MeowyConfig.get().helmetOpacity;
            if (opacity <= 0.001f) {
                ci.cancel();
                return;
            }
            RenderOpacityContext.push(RenderOpacityContext.Target.HELMET, opacity);
        }
    }

    @Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;FF)V", at = @At("RETURN"))
    private void meowy$afterCustomHeadSubmit(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, S state, float yRot, float xRot, CallbackInfo ci) {
        if (((MeowyRenderStateExt) state).meowy$isLocalPlayer() && MeowyConfig.get().helmetOpacity > 0.001f) {
            RenderOpacityContext.pop();
        }
    }
}
