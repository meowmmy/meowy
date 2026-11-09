package dev.meowy.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.meowy.client.config.MeowyConfig;
import dev.meowy.client.render.RenderOpacityContext;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies Player Model Opacity to the local player's first-person empty hand rendering.
 */
@Mixin(AvatarRenderer.class)
public class AvatarRendererMixin {

    @Inject(method = {"renderRightHand", "renderLeftHand"}, at = @At("HEAD"), cancellable = true)
    private void meowy$beforeFirstPersonHand(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, Identifier skinTexture, boolean hasSleeve, CallbackInfo ci) {
        float opacity = MeowyConfig.get().playerModelOpacity;
        if (opacity <= 0.001f) {
            ci.cancel();
            return;
        }
        RenderOpacityContext.push(RenderOpacityContext.Target.PLAYER_BODY, opacity);
    }

    @Inject(method = {"renderRightHand", "renderLeftHand"}, at = @At("RETURN"))
    private void meowy$afterFirstPersonHand(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, Identifier skinTexture, boolean hasSleeve, CallbackInfo ci) {
        if (MeowyConfig.get().playerModelOpacity > 0.001f) {
            RenderOpacityContext.pop();
        }
    }

    @WrapOperation(
        method = "*",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/rendertype/RenderTypes;entitySolid(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/client/renderer/rendertype/RenderType;"),
        require = 0
    )
    private RenderType meowy$useTranslucentHandLayer(Identifier texture, Operation<RenderType> original) {
        if (RenderOpacityContext.isActive()) {
            return RenderTypes.entityTranslucent(texture);
        }
        return original.call(texture);
    }

    @Inject(
        method = "shouldShowName",
        at = @At("RETURN"),
        cancellable = true,
        require = 0
    )
    private void meowy$allowOwnAvatarNametag(net.minecraft.world.entity.Avatar entity, double distanceToCameraSq, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && MeowyConfig.get().showOwnNametag && !dev.meowy.client.render.MeowyPreviewContext.isRenderingPreview()) {
            net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
            if (client.player != null
                && entity == client.player
                && !client.options.getCameraType().isFirstPerson()
                && !entity.isVehicle()) {
                cir.setReturnValue(true);
            }
        }
    }
}
