package dev.ow.client.skin;

import dev.ow.client.animation.BowPullTracker;
import dev.ow.client.animation.BowPullTracker.PullStage;
import dev.ow.client.config.OwConfig;
import dev.ow.client.skin.BowRenderContext.Frame;
import dev.ow.client.skin.BowRenderContext.OwnerScope;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Central client-side bow detection and model override resolver.
 * Every bow (vanilla bows and SkyBlock shortbows alike) is treated identically.
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
        if (!OwConfig.get().isBowSkinEnabled()) {
            return null;
        }
        if (!isBow(stack, originalModelId)) {
            return null;
        }
        if (!BowRenderContext.shouldOverrideForLocalPlayer(stack)) {
            return null;
        }

        BowSkin selectedSkin = OwConfig.get().getSelectedBowSkin();
        Frame frame = BowRenderContext.current();

        if (frame != null && frame.scope() == OwnerScope.OW_GUI_PREVIEW) {
            PullStage previewStage = frame.previewStage() != null ? frame.previewStage() : PullStage.IDLE;
            return selectedSkin.itemModelForStage(previewStage.stageIndex());
        }

        ItemDisplayContext displayContext = frame != null ? frame.displayContext() : ItemDisplayContext.GUI;
        PullStage stage = BowPullTracker.getRenderPullStage(stack, displayContext);
        return selectedSkin.itemModelForStage(stage.stageIndex());
    }
}
