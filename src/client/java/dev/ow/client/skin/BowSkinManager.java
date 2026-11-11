package dev.ow.client.skin;

import dev.ow.client.config.OwConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Central client-side bow detection and model override resolver.
 */
public final class BowSkinManager {

    private static final Identifier VANILLA_BOW_MODEL_ID = Identifier.withDefaultNamespace("bow");

    private BowSkinManager() {}

    public static boolean isBow(ItemStack stack, Identifier originalModelId) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        var item = stack.getItem();
        if (item == Items.BOW || item instanceof BowItem) {
            return true;
        }
        return VANILLA_BOW_MODEL_ID.equals(originalModelId);
    }

    public static Identifier resolveBowModelOverride(ItemStack stack, Identifier originalModelId) {
        if (!OwConfig.get().isBowSkinEnabled() || !isBow(stack, originalModelId)) {
            return null;
        }
        if (!BowRenderContext.shouldOverrideForLocalPlayer(stack)) {
            return null;
        }

        BowSkin selectedSkin = OwConfig.get().getSelectedBowSkin();
        ItemDisplayContext displayContext = BowRenderContext.currentDisplayContext();
        return isPullingBow(stack, displayContext)
            ? selectedSkin.pullingItemModelId()
            : selectedSkin.idleItemModelId();
    }

    private static boolean isPullingBow(ItemStack stack, ItemDisplayContext displayContext) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || !client.player.isUsingItem()) {
            return false;
        }
        LocalPlayer player = client.player;
        boolean isHeldStack = (stack == player.getMainHandItem() || stack == player.getOffhandItem());
        boolean isHandContext = displayContext == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
            || displayContext == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
            || displayContext == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
            || displayContext == ItemDisplayContext.THIRD_PERSON_LEFT_HAND;
        if (!isHandContext && !isHeldStack) {
            return false;
        }
        ItemStack useItem = player.getUseItem();
        return useItem == stack || (isHandContext && isBow(useItem, null));
    }
}
