package dev.meowy.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.meowy.client.config.MeowyConfig;
import dev.meowy.client.render.MeowyPreviewContext;
import dev.meowy.client.render.MeowyRenderStateExt;
import dev.meowy.client.render.RenderOpacityContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Applies Player Model Opacity strictly to the local player (Minecraft.getInstance().player),
 * and applies user drag-to-rotate orientation with a stillness pose in the 3D Live Preview card.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin<T extends LivingEntity, S extends LivingEntityRenderState, M extends EntityModel<? super S>> {

    @Shadow
    public abstract Identifier getTextureLocation(S state);

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V", at = @At("RETURN"))
    private void meowy$markLocalPlayerAndPreviewOrientation(T entity, S state, float partialTicks, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        boolean isSelf = client.player != null && entity == client.player;
        MeowyRenderStateExt ext = (MeowyRenderStateExt) state;
        ext.meowy$setLocalPlayer(isSelf);

        if (isSelf && MeowyPreviewContext.isRenderingPreview()) {
            ext.meowy$setPreviewEntity(true);
            ext.meowy$setPreviewYawOffset(MeowyPreviewContext.getPreviewYaw());
            ext.meowy$setPreviewPitch(MeowyPreviewContext.getPreviewPitch());
            state.walkAnimationPos = 0.0f;
            state.walkAnimationSpeed = 0.0f;
        } else {
            ext.meowy$setPreviewEntity(false);
        }
    }

    @Inject(method = "getRenderType", at = @At("HEAD"), cancellable = true)
    private void meowy$overrideLocalPlayerRenderType(S state, boolean isBodyVisible, boolean isTranslucent, boolean appearsGlowing, CallbackInfoReturnable<RenderType> cir) {
        if (((MeowyRenderStateExt) state).meowy$isLocalPlayer()) {
            float opacity = MeowyConfig.get().playerModelOpacity;
            if (opacity <= 0.001f) {
                cir.setReturnValue(null);
            } else if (opacity < 0.999f) {
                Identifier texture = this.getTextureLocation(state);
                cir.setReturnValue(RenderTypes.entityTranslucent(texture));
            }
        }
    }

    @Inject(method = "getModelTint", at = @At("RETURN"), cancellable = true)
    private void meowy$applyLocalPlayerModelTint(S state, CallbackInfoReturnable<Integer> cir) {
        if (((MeowyRenderStateExt) state).meowy$isLocalPlayer()) {
            float opacity = MeowyConfig.get().playerModelOpacity;
            if (opacity < 0.999f) {
                RenderOpacityContext.push(RenderOpacityContext.Target.PLAYER_BODY, opacity);
                try {
                    cir.setReturnValue(RenderOpacityContext.applyAlphaToColor(cir.getReturnValue()));
                } finally {
                    RenderOpacityContext.pop();
                }
            }
        }
    }

    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V", at = @At("HEAD"))
    private void meowy$beginPlayerSubmit(S state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState cameraState, CallbackInfo ci) {
        MeowyRenderStateExt ext = (MeowyRenderStateExt) state;
        if (ext.meowy$isPreviewEntity()) {
            // Apply drag-to-rotate orientation right before setupRotations & model.setupAnim
            // (after InventoryScreen.extractEntityInInventoryFollowsMouse has finished modifying state)
            state.walkAnimationPos = 0.0f;
            state.walkAnimationSpeed = 0.0f;
            state.bodyRot = 180.0f - ext.meowy$getPreviewYawOffset();
            state.yRot = 0.0f;
            state.xRot = ext.meowy$getPreviewPitch();
        }
        if (ext.meowy$isLocalPlayer()) {
            RenderOpacityContext.push(RenderOpacityContext.Target.PLAYER_BODY, MeowyConfig.get().playerModelOpacity);
        }
    }

    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V", at = @At("RETURN"))
    private void meowy$endPlayerSubmit(S state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState cameraState, CallbackInfo ci) {
        if (((MeowyRenderStateExt) state).meowy$isLocalPlayer()) {
            RenderOpacityContext.pop();
        }
    }

    @Inject(
        method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;D)Z",
        at = @At("RETURN"),
        cancellable = true,
        require = 0
    )
    private void meowy$showOwnPlayerNametag(T entity, double distanceToCameraSq, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && MeowyConfig.get().showOwnNametag && !MeowyPreviewContext.isRenderingPreview()) {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null
                && entity == client.player
                && !client.options.getCameraType().isFirstPerson()
                && !entity.isVehicle()) {
                cir.setReturnValue(true);
            }
        }
    }
}
