package dev.meowy.client.mixin;

import dev.ow.client.config.OwConfig;
import dev.ow.client.projectile.LocalArrowTracker;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Arrow.class)
public abstract class ArrowMixin {

    @Inject(method = "makeParticle", at = @At("HEAD"), cancellable = true, require = 0)
    private void ow$suppressLocalArrowEffectParticles(int amount, CallbackInfo ci) {
        Arrow self = (Arrow) (Object) this;
        if (self.level().isClientSide() && OwConfig.get().laserBulletEnabled && LocalArrowTracker.isLocalPlayerArrow(self)) {
            ci.cancel();
        }
    }

    @Inject(method = "handleEntityEvent", at = @At("HEAD"), cancellable = true, require = 0)
    private void ow$suppressLocalArrowPotionPuff(byte id, CallbackInfo ci) {
        if (id == 0) {
            Arrow self = (Arrow) (Object) this;
            if (self.level().isClientSide() && OwConfig.get().laserBulletEnabled && LocalArrowTracker.isLocalPlayerArrow(self)) {
                ci.cancel();
            }
        }
    }
}
