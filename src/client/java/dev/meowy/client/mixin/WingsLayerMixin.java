package dev.meowy.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.meowy.client.config.MeowyConfig;
import dev.meowy.client.render.MeowyRenderStateExt;
import dev.meowy.client.render.RenderOpacityContext;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.WingsLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies Chestplate Opacity to equipped Elytra (and Elytra trims/glint) on the local player.
 */
@Mixin(WingsLayer.class)
public class WingsLayerMixin<S extends HumanoidRenderState> {

    @Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/HumanoidRenderState;FF)V", at = @At("HEAD"), cancellable = true)
    private void meowy$beforeWingsSubmit(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, S state, float yRot, float xRot, CallbackInfo ci) {
        if (((MeowyRenderStateExt) state).meowy$isLocalPlayer()) {
            float opacity = MeowyConfig.get().chestplateOpacity;
            if (opacity <= 0.001f) {
                ci.cancel();
                return;
            }
            RenderOpacityContext.push(opacity, RenderOpacityContext.ORDER_ARMOR);
        }
    }

    @Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/HumanoidRenderState;FF)V", at = @At("RETURN"))
    private void meowy$afterWingsSubmit(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, S state, float yRot, float xRot, CallbackInfo ci) {
        if (((MeowyRenderStateExt) state).meowy$isLocalPlayer() && MeowyConfig.get().chestplateOpacity > 0.001f) {
            RenderOpacityContext.pop();
        }
    }
}
