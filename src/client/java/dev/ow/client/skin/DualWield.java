package dev.ow.client.skin;

import dev.ow.client.config.OwConfig;
import dev.ow.client.config.OwTuning;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Visual-only dual-wield support for skins flagged with {@link BowSkin#dualWield()} (Tracer's Pulse Pistol).
 *
 * <p>When the local player holds a bow in one hand and the other hand is <b>empty</b>, the empty hand is drawn
 * holding a second pistol. This is done by handing the renderer a private, never-inserted "proxy" bow stack in
 * place of the empty stack, which the normal skin override then turns into the pistol model.
 *
 * <p><b>100% client side:</b> the proxy stack is a local object that is never put in an inventory, container or
 * packet. The server never learns about it. It is only ever used for rendering the local player's own hands, and
 * it is never substituted for a hand that actually holds an item (a shield, a totem, another weapon, ...).
 */
public final class DualWield {

    /** Lazily created so the class can be loaded before item registries are bootstrapped. */
    private static final class ProxyHolder {
        static final ItemStack PROXY_BOW = new ItemStack(Items.BOW);
    }

    private DualWield() {}

    private static boolean featureEnabled() {
        return OwConfig.get().isBowSkinEnabled() && OwTuning.TRACER_DUAL_WIELD && OwConfig.get().getSelectedBowSkin().dualWield();
    }

    private static boolean isBow(ItemStack stack) {
        return stack != null && !stack.isEmpty() && BowSkinManager.isBow(stack, null);
    }

    private static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.isEmpty();
    }

    /**
     * True while the local player holds a bow in exactly one hand and the other hand is empty, with a
     * dual-wield skin selected and the feature enabled.
     */
    public static boolean isActive() {
        if (!featureEnabled()) {
            return false;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }
        ItemStack main = player.getMainHandItem();
        ItemStack off = player.getOffhandItem();
        return (isBow(main) && isEmpty(off)) || (isBow(off) && isEmpty(main));
    }

    /**
     * First-person hand rendering. Called with the stack the vanilla hand renderer is about to draw for a hand.
     * If that hand is the empty one of an active dual-wield setup, returns the proxy pistol stack; otherwise
     * returns {@code original} untouched.
     *
     * <p>Only an empty stack is ever replaced. In an active setup there is exactly one empty hand, so an empty
     * stack here can only belong to it.
     */
    public static ItemStack firstPersonStack(ItemStack original) {
        if (original != null && !original.isEmpty()) {
            return original;
        }
        return isActive() ? ProxyHolder.PROXY_BOW : original;
    }

    /**
     * Third-person / model-based rendering of the local player's hands (also used by the inventory paper doll).
     * Replaces the empty stack of the empty arm with the proxy pistol when the opposite arm holds a bow.
     */
    public static ItemStack thirdPersonStack(ItemStack original, ItemDisplayContext context, LivingEntity entity) {
        if (original != null && !original.isEmpty()) {
            return original;
        }
        if (context != ItemDisplayContext.THIRD_PERSON_LEFT_HAND && context != ItemDisplayContext.THIRD_PERSON_RIGHT_HAND) {
            return original;
        }
        if (!featureEnabled()) {
            return original;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || entity != player) {
            return original;
        }

        HumanoidArm thisArm = context == ItemDisplayContext.THIRD_PERSON_LEFT_HAND ? HumanoidArm.LEFT : HumanoidArm.RIGHT;
        HumanoidArm otherArm = thisArm == HumanoidArm.LEFT ? HumanoidArm.RIGHT : HumanoidArm.LEFT;

        if (isEmpty(heldBy(player, thisArm)) && isBow(heldBy(player, otherArm))) {
            return ProxyHolder.PROXY_BOW;
        }
        return original;
    }

    private static ItemStack heldBy(LocalPlayer player, HumanoidArm arm) {
        return player.getMainArm() == arm ? player.getMainHandItem() : player.getOffhandItem();
    }
}
