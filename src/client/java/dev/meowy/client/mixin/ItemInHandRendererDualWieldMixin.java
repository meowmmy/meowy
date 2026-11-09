package dev.meowy.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.meowy.client.MeowyClient;
import dev.ow.client.skin.DualWield;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * First-person dual-wield for Tracer's Pulse Pistol (visual only, local player only).
 *
 * <p>Lives in {@code ow.dualwield.mixins.json}, a <b>non-required</b> mixin config with {@code require = 0}
 * on every injector. These hooks target hand-renderer internals that were renamed in 26.2
 * ({@code renderHandsWithItems -> submitHandsWithItems}, {@code renderArmWithItem -> submitArmWithItem}); if a
 * name or shape does not match, Mixin skips the hook and the rest of OW keeps working with a normal single
 * pistol instead of the game failing to start.
 *
 * <p>Both injectors are descriptor-free (they match by method / field name only) so they do not depend on the
 * exact parameter list of the vanilla methods.
 */
@Mixin(ItemInHandRenderer.class)
public class ItemInHandRendererDualWieldMixin {

    /**
     * Swaps the empty stack of the empty hand for the proxy pistol so the vanilla code renders an item (the
     * pistol) instead of nothing / a bare arm. Non-empty stacks are returned unchanged.
     */
    @ModifyVariable(method = "submitArmWithItem", at = @At("HEAD"), argsOnly = true, require = 0)
    private ItemStack ow$dualWieldProxyStack(ItemStack stack) {
        try {
            return DualWield.firstPersonStack(stack);
        } catch (Throwable t) {
            MeowyClient.logOnce("ItemInHandRendererDualWieldMixin.proxyStack", t);
            return stack;
        }
    }

    /**
     * While a bow is being drawn vanilla hides the other hand. With dual-wield active, keep both hands drawn.
     */
    @ModifyExpressionValue(
        method = "submitHandsWithItems",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/client/renderer/ItemInHandRenderer$HandRenderSelection;renderMainHand:Z"
        ),
        require = 0
    )
    private boolean ow$keepMainHandVisible(boolean original) {
        return original || ow$dualWieldActive();
    }

    @ModifyExpressionValue(
        method = "submitHandsWithItems",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/client/renderer/ItemInHandRenderer$HandRenderSelection;renderOffHand:Z"
        ),
        require = 0
    )
    private boolean ow$keepOffHandVisible(boolean original) {
        return original || ow$dualWieldActive();
    }

    private static boolean ow$dualWieldActive() {
        try {
            return DualWield.isActive();
        } catch (Throwable t) {
            MeowyClient.logOnce("ItemInHandRendererDualWieldMixin.active", t);
            return false;
        }
    }
}
