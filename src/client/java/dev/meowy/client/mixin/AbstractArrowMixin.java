package dev.meowy.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.meowy.client.MeowyClient;
import dev.meowy.client.config.MeowyConfig;
import dev.meowy.client.render.CrosshairProjectionHelper;
import dev.meowy.client.render.HitmarkerManager;
import dev.ow.client.config.OwConfig;
import dev.ow.client.config.OwTuning;
import dev.ow.client.projectile.LocalArrowTracker;
import dev.ow.client.render.LaserBulletRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractArrow.class)
public abstract class AbstractArrowMixin {

    /**
     * When Laser Bullets is enabled, prevent client-side arrow bounce-off on entity hit and mark the bullet
     * terminated immediately so it vanishes on impact like a real bullet rather than bouncing and falling.
     */
    @Inject(method = "onHitEntity", at = @At("HEAD"), cancellable = true, require = 0)
    private void ow$terminateLaserBulletOnEntityHit(EntityHitResult hitResult, CallbackInfo ci) {
        AbstractArrow self = (AbstractArrow) (Object) this;
        if (!self.level().isClientSide() || !LocalArrowTracker.isLocalPlayerArrow(self)) {
            return;
        }
        if (MeowyConfig.get().hitmarkersEnabled && hitResult != null && hitResult.getEntity() != null) {
            Minecraft client = Minecraft.getInstance();
            if (client != null && client.player != null
                && CrosshairProjectionHelper.isValidTargetEntity(client.player, hitResult.getEntity())) {
                HitmarkerManager.triggerHit(
                    "client_on_hit_entity",
                    hitResult.getEntity().getId(),
                    hitResult.getEntity().getName().getString(),
                    0.0
                );
            }
        }
        if (OwConfig.get().laserBulletEnabled) {
            LaserBulletRenderer.markTerminated(self.getId());
            ci.cancel();
        }
    }

    /**
     * Energy trail: coloured dust motes along the bolt's path each tick (client only, local arrows only).
     * Stops immediately if the bullet has hit an entity or bounced.
     */
    @Inject(method = "tick", at = @At("TAIL"), require = 0)
    private void ow$energyTrail(CallbackInfo ci) {
        AbstractArrow self = (AbstractArrow) (Object) this;
        Level level = self.level();
        OwConfig cfg = OwConfig.get();
        if (!level.isClientSide() || !cfg.laserBulletEnabled) {
            return;
        }
        try {
            if (!LocalArrowTracker.isLocalPlayerArrow(self)) {
                return;
            }
            if (LaserBulletRenderer.checkAndUpdateTermination(self)) {
                return;
            }
            Minecraft client = Minecraft.getInstance();
            boolean firstPerson = client == null || client.options == null || client.options.getCameraType().isFirstPerson();
            float scale = firstPerson ? OwTuning.FIRST_PERSON_TRAIL_SCALE : OwTuning.THIRD_PERSON_TRAIL_SCALE;

            Vec3 pos = self.position();
            Vec3 vel = self.getDeltaMovement();
            int rgb = LaserBulletRenderer.GLOW_RGB;
            ParticleOptions dust = new DustParticleOptions(rgb, scale);
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
