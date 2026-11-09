package dev.meowy.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ow.client.config.OwConfig;
import dev.ow.client.config.OwTuning;
import dev.ow.client.projectile.LocalArrowTracker;
import dev.meowy.client.MeowyClient;
import dev.ow.client.config.ColorUtil;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AbstractArrow.class)
public abstract class AbstractArrowMixin {

    /**
     * Energy trail: a few coloured dust motes along the bolt's path each tick (client only, local arrows only).
     * Uses the same {@code Projectile Color} as the bolt, so colour changes apply to new particles instantly.
     */
    @Inject(method = "tick", at = @At("TAIL"), require = 0)
    private void ow$energyTrail(CallbackInfo ci) {
        AbstractArrow self = (AbstractArrow) (Object) this;
        Level level = self.level();
        if (!level.isClientSide() || !OwConfig.get().laserBulletEnabled) {
            return;
        }
        try {
            if (((AbstractArrowAccessor) self).ow$isInGround() || !LocalArrowTracker.isLocalPlayerArrow(self)) {
                return;
            }
            Vec3 pos = self.position();
            Vec3 vel = self.getDeltaMovement();
            int rgb = ColorUtil.renderGlowColor(OwTuning.PROJECTILE_RGB);
            ParticleOptions dust = new DustParticleOptions(rgb, 0.55f);
            level.addParticle(dust, pos.x, pos.y + 0.25, pos.z, 0.0, 0.0, 0.0);
            level.addParticle(dust, pos.x - vel.x * 0.5, pos.y + 0.25 - vel.y * 0.5, pos.z - vel.z * 0.5, 0.0, 0.0, 0.0);
        } catch (Throwable t) {
            MeowyClient.logOnce("AbstractArrowMixin.trail", t);
        }
    }

    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"
        ),
        require = 0
    )
    private void ow$suppressLocalArrowCritAndBubbleParticles(
        Level level,
        ParticleOptions particle,
        double x,
        double y,
        double z,
        double dx,
        double dy,
        double dz,
        Operation<Void> original
    ) {
        AbstractArrow self = (AbstractArrow) (Object) this;
        if (level.isClientSide() && OwConfig.get().laserBulletEnabled && LocalArrowTracker.isLocalPlayerArrow(self)) {
            return;
        }
        original.call(level, particle, x, y, z, dx, dy, dz);
    }
}
