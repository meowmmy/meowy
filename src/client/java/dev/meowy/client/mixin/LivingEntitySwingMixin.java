package dev.meowy.client.mixin;

import dev.meowy.client.config.MeowyConfig;
import dev.meowy.client.render.MeowyPreviewContext;
import dev.ow.client.skin.BowSkinManager;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Implements a 100% client-side floating-point swing progress accumulator for the local player,
 * and completely suppresses the visual swing/punch animation on bows when Custom Bow Skins is enabled.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntitySwingMixin {

    @Shadow
    public boolean swinging;

    @Shadow
    public int swingTime;

    @Shadow
    public float attackAnim;

    @Shadow
    public float oAttackAnim;

    @Shadow
    protected abstract int getCurrentSwingDuration();

    @Unique
    private float meowy$smoothSwingProgress = 0.0f;

    @Unique
    private float meowy$prevSmoothSwingProgress = 0.0f;

    @Unique
    private static boolean meowy$shouldSuppressBowSwing(LivingEntity self) {
        if (!MeowyConfig.get().bowSkinsEnabled || MeowyPreviewContext.isPreviewSwingActive()) {
            return false;
        }
        return BowSkinManager.isBow(self.getMainHandItem(), null)
            || BowSkinManager.isBow(self.getOffhandItem(), null);
    }

    @Inject(method = "swing(Lnet/minecraft/world/InteractionHand;Z)V", at = @At("HEAD"))
    private void meowy$onStartSwing(InteractionHand hand, boolean updateSelf, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        Minecraft client = Minecraft.getInstance();
        if (client != null && self == client.player && self.level().isClientSide()) {
            if (meowy$shouldSuppressBowSwing(self)) {
                this.meowy$smoothSwingProgress = 0.0f;
                this.meowy$prevSmoothSwingProgress = 0.0f;
                return;
            }
            if (!this.swinging || this.meowy$smoothSwingProgress >= 0.5f || this.swingTime < 0) {
                this.meowy$smoothSwingProgress = 0.0f;
                this.meowy$prevSmoothSwingProgress = 0.0f;
            }
        }
    }

    @Inject(method = "updateSwingTime", at = @At("HEAD"), cancellable = true)
    private void meowy$smoothUpdateSwingTime(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        Minecraft client = Minecraft.getInstance();
        if (client != null && self == client.player && self.level().isClientSide()) {
            if (meowy$shouldSuppressBowSwing(self)) {
                this.meowy$smoothSwingProgress = 0.0f;
                this.meowy$prevSmoothSwingProgress = 0.0f;
                this.swingTime = 0;
                this.swinging = false;
                this.attackAnim = 0.0f;
                this.oAttackAnim = 0.0f;
                ci.cancel();
                return;
            }

            float speed = MeowyConfig.get().visualSwingSpeed;
            if (Math.abs(speed - 1.0f) > 0.005f) {
                int baseDuration = Math.max(1, this.getCurrentSwingDuration());
                this.oAttackAnim = this.attackAnim;

                if (this.swinging) {
                    if (this.meowy$smoothSwingProgress >= 1.0f) {
                        this.meowy$smoothSwingProgress = 0.0f;
                        this.meowy$prevSmoothSwingProgress = 0.0f;
                        this.swingTime = 0;
                        this.swinging = false;
                    } else {
                        this.meowy$prevSmoothSwingProgress = this.meowy$smoothSwingProgress;
                        float step = speed / (float) baseDuration;
                        this.meowy$smoothSwingProgress += step;
                        this.swingTime = Math.min(baseDuration - 1, (int) Math.floor(Math.min(0.999f, this.meowy$smoothSwingProgress) * baseDuration));
                    }
                } else {
                    this.meowy$smoothSwingProgress = 0.0f;
                    this.meowy$prevSmoothSwingProgress = 0.0f;
                    this.swingTime = 0;
                }

                this.attackAnim = Math.min(1.0f, this.meowy$smoothSwingProgress);
                ci.cancel();
            }
        }
    }

    @Inject(method = "getAttackAnim", at = @At("HEAD"), cancellable = true)
    private void meowy$getSmoothAttackAnim(float partialTicks, CallbackInfoReturnable<Float> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        Minecraft client = Minecraft.getInstance();
        if (client != null && self == client.player && self.level().isClientSide()) {
            if (MeowyPreviewContext.isPreviewSwingActive()) {
                cir.setReturnValue(MeowyPreviewContext.getPreviewSwingProgress());
                return;
            }

            if (meowy$shouldSuppressBowSwing(self)) {
                cir.setReturnValue(0.0f);
                return;
            }

            float realPartialTicks = MeowyPreviewContext.isRenderingPreview()
                ? client.getDeltaTracker().getGameTimeDeltaPartialTick(true)
                : partialTicks;

            float speed = MeowyConfig.get().visualSwingSpeed;
            if (Math.abs(speed - 1.0f) > 0.005f) {
                if (!this.swinging && this.meowy$smoothSwingProgress == 0.0f && this.meowy$prevSmoothSwingProgress == 0.0f) {
                    cir.setReturnValue(0.0f);
                    return;
                }
                float interpolated = Mth.lerp(realPartialTicks, this.meowy$prevSmoothSwingProgress, this.meowy$smoothSwingProgress);
                cir.setReturnValue(Mth.clamp(interpolated, 0.0f, 1.0f));
            } else if (MeowyPreviewContext.isRenderingPreview()) {
                float diff = this.attackAnim - this.oAttackAnim;
                if (diff < 0.0f) {
                    diff += 1.0f;
                }
                cir.setReturnValue(Mth.clamp(this.oAttackAnim + diff * realPartialTicks, 0.0f, 1.0f));
            }
        }
    }
}
