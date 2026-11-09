package dev.meowy.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.meowy.client.MeowyClient;
import dev.meowy.client.config.MeowyConfig;
import dev.meowy.client.render.MeowyPreviewContext;
import dev.ow.client.animation.CassidyAnimator;
import dev.ow.client.animation.PistolAnimator;
import dev.ow.client.skin.BowRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Implements HAND category features & OW first-person weapon animations:
 * - Purely client-side high-FPS swing preview override when dragging Visual Swing Speed in the GUI
 * - Held Item X, Y, Z viewmodel position translation
 * - Held Item Side-to-Side and Forwards/Backwards rotations
 * - Held Item Scale (0.20x - 2.50x size scaling for the local player's held weapon/item)
 * - Custom Bow Skin first-person recoil & flourish animations (Peacekeeper & Pulse Pistol)
 */
@Mixin(ItemInHandRenderer.class)
public class ItemInHandRendererMixin {

    @Unique
    private boolean meowy$pushedHeldItemPose = false;

    @Unique
    private boolean meowy$pushedOwAnim = false;

    @ModifyVariable(
        method = "submitArmWithItem",
        at = @At("HEAD"),
        argsOnly = true,
        ordinal = 2,
        require = 0
    )
    private float meowy$overridePreviewSwingProgress(
        float swingProgress,
        AbstractClientPlayer player,
        float partialTicks,
        float pitch,
        InteractionHand hand
    ) {
        if (hand == InteractionHand.MAIN_HAND && MeowyPreviewContext.isPreviewSwingActive()) {
            return MeowyPreviewContext.getPreviewSwingProgress();
        }
        return swingProgress;
    }

    @Inject(method = "submitArmWithItem", at = @At("HEAD"))
    private void meowy$applyViewmodelTranslation(
        AbstractClientPlayer player,
        float partialTicks,
        float pitch,
        InteractionHand hand,
        float swingProgress,
        ItemStack stack,
        float equipProgress,
        PoseStack poseStack,
        SubmitNodeCollector collector,
        int lightCoords,
        CallbackInfo ci
    ) {
        if (!stack.isEmpty()) {
            MeowyConfig cfg = MeowyConfig.get();
            HumanoidArm arm = hand == InteractionHand.MAIN_HAND ? player.getMainArm() : player.getMainArm().getOpposite();
            float handSign = arm == HumanoidArm.RIGHT ? 1.0f : -1.0f;
            poseStack.translate(cfg.heldItemX * handSign, cfg.heldItemY, cfg.heldItemZ);
        }
    }

    @Inject(method = "renderItem", at = @At("HEAD"))
    private void meowy$beforeRenderHeldItem(
        LivingEntity entity,
        ItemStack stack,
        ItemDisplayContext displayContext,
        PoseStack poseStack,
        SubmitNodeCollector collector,
        int lightCoords,
        CallbackInfo ci
    ) {
        this.meowy$pushedHeldItemPose = false;
        this.meowy$pushedOwAnim = false;

        BowRenderContext.pushLiving(entity, displayContext);
        try {
            if (MeowyConfig.get().bowSkinsEnabled) {
                this.meowy$pushedOwAnim = PistolAnimator.applyFirstPerson(entity, stack, displayContext, poseStack)
                    || CassidyAnimator.applyFirstPerson(entity, stack, displayContext, poseStack);
            }
        } catch (Throwable t) {
            this.meowy$pushedOwAnim = false;
            MeowyClient.logOnce("ItemInHandRendererMixin.owAnimation", t);
        }

        Minecraft client = Minecraft.getInstance();
        if (entity != null && entity == client.player && !stack.isEmpty()) {
            MeowyConfig cfg = MeowyConfig.get();
            float scale = cfg.heldItemScale;
            float rotSide = cfg.heldItemRotSide;
            float rotForward = cfg.heldItemRotForward;
            boolean hasTransform = Math.abs(scale - 1.0f) > 0.001f
                || Math.abs(rotSide) > 0.001f
                || Math.abs(rotForward) > 0.001f;

            if (hasTransform) {
                poseStack.pushPose();
                this.meowy$pushedHeldItemPose = true;

                float handSign = (displayContext == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
                    || displayContext == ItemDisplayContext.THIRD_PERSON_LEFT_HAND) ? -1.0f : 1.0f;

                if (Math.abs(rotForward) > 0.001f) {
                    poseStack.mulPose(Axis.XP.rotationDegrees(rotForward));
                }
                if (Math.abs(rotSide) > 0.001f) {
                    poseStack.mulPose(Axis.ZP.rotationDegrees(rotSide * handSign));
                }
                if (Math.abs(scale - 1.0f) > 0.001f) {
                    poseStack.scale(scale, scale, scale);
                }
            }
        }
    }

    @Inject(method = "renderItem", at = @At("RETURN"))
    private void meowy$afterRenderHeldItem(
        LivingEntity entity,
        ItemStack stack,
        ItemDisplayContext displayContext,
        PoseStack poseStack,
        SubmitNodeCollector collector,
        int lightCoords,
        CallbackInfo ci
    ) {
        if (this.meowy$pushedHeldItemPose) {
            poseStack.popPose();
            this.meowy$pushedHeldItemPose = false;
        }
        if (this.meowy$pushedOwAnim) {
            poseStack.popPose();
            this.meowy$pushedOwAnim = false;
        }
        BowRenderContext.pop();
    }
}
