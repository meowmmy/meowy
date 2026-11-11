package dev.meowy.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.meowy.client.config.MeowyConfig;
import dev.meowy.client.render.MeowyRenderStateExt;
import dev.meowy.client.render.RenderOpacityContext;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Controls the local player's cape opacity independently via MeowyConfig.capeOpacity.
 */
@Mixin(CapeLayer.class)
public class CapeLayerMixin {

    @Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V", at = @At("HEAD"), cancellable = true)
    private void meowy$beforeCapeSubmit(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, AvatarRenderState state, float yRot, float xRot, CallbackInfo ci) {
        if (((MeowyRenderStateExt) state).meowy$isLocalPlayer()) {
            float opacity = MeowyConfig.get().capeOpacity;
            if (opacity <= 0.001f) {
                ci.cancel();
                return;
            }
            RenderOpacityContext.push(opacity, RenderOpacityContext.ORDER_CAPE);
        }
    }

    @Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V", at = @At("RETURN"))
    private void meowy$afterCapeSubmit(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, AvatarRenderState state, float yRot, float xRot, CallbackInfo ci) {
        if (((MeowyRenderStateExt) state).meowy$isLocalPlayer() && MeowyConfig.get().capeOpacity > 0.001f) {
            RenderOpacityContext.pop();
        }
    }

    @WrapOperation(
        method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/rendertype/RenderTypes;entitySolid(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/client/renderer/rendertype/RenderType;"),
        require = 0
    )
    private RenderType meowy$useTranslucentCapeRenderType(Identifier texture, Operation<RenderType> original) {
        if (RenderOpacityContext.isActive()) {
            return RenderTypes.entityTranslucent(texture);
        }
        return original.call(texture);
    }

    @WrapOperation(
        method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/rendertype/RenderTypes;entityCutout(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/client/renderer/rendertype/RenderType;"),
        require = 0
    )
    private RenderType meowy$useTranslucentCapeCutoutRenderType(Identifier texture, Operation<RenderType> original) {
        if (RenderOpacityContext.isActive()) {
            return RenderTypes.entityTranslucent(texture);
        }
        return original.call(texture);
    }
}
