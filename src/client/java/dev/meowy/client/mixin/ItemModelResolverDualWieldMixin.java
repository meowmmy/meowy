package dev.meowy.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.meowy.client.MeowyClient;
import dev.ow.client.skin.DualWield;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Third-person dual-wield (also covers the inventory paper doll): when the local player's empty arm is resolved
 * for rendering, hand the resolver the proxy pistol instead of the empty stack.
 *
 * <p>Lives in the non-required {@code ow.dualwield.mixins.json}. Uses the same {@code updateForLiving} signature
 * the main {@link ItemModelResolverMixin} already targets.
 */
@Mixin(ItemModelResolver.class)
public class ItemModelResolverDualWieldMixin {

    @WrapMethod(method = "updateForLiving", require = 0)
    private void ow$dualWieldThirdPerson(
        ItemStackRenderState renderState,
        ItemStack stack,
        ItemDisplayContext displayContext,
        LivingEntity entity,
        Operation<Void> original
    ) {
        ItemStack effective = stack;
        try {
            effective = DualWield.thirdPersonStack(stack, displayContext, entity);
        } catch (Throwable t) {
            MeowyClient.logOnce("ItemModelResolverDualWieldMixin.thirdPerson", t);
        }
        original.call(renderState, effective, displayContext, entity);
    }
}
