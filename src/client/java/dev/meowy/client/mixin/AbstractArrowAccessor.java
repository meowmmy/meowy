package dev.meowy.client.mixin;

import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes the protected {@code AbstractArrow#isInGround()} so the laser renderer can hide the beam
 * once the arrow has stuck in a block. Purely a read-only client-side accessor.
 */
@Mixin(AbstractArrow.class)
public interface AbstractArrowAccessor {

    @Invoker("isInGround")
    boolean ow$isInGround();
}
