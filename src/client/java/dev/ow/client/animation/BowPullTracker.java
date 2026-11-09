package dev.ow.client.animation;

import dev.ow.client.skin.BowSkinManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Resolves which bow model stage ({@code bow}, {@code bow_pulling_0}, {@code bow_pulling_1},
 * {@code bow_pulling_2}) to show for the local player's bow.
 *
 * <p>Every bow, including SkyBlock shortbows such as the Terminator or Juju, uses the exact same logic as a
 * vanilla bow: idle model when not drawing, then the vanilla draw stages while the player is using the item.
 * There is no special-casing, locking or timing state.
 */
public final class BowPullTracker {

    public enum PullStage {
        IDLE(0),
        PULLING_0(1),
        PULLING_1(2),
        PULLING_2(3);

        private final int stageIndex;

        PullStage(int stageIndex) {
            this.stageIndex = stageIndex;
        }

        public int stageIndex() {
            return this.stageIndex;
        }
    }

    private BowPullTracker() {}

    /**
     * Vanilla thresholds (identical to vanilla's {@code use_duration} range dispatch with scale 0.05):
     * under 0.65 -> pulling_0, 0.65 to 0.9 -> pulling_1, 0.9 and above -> pulling_2.
     */
    public static PullStage stageForUseTime(float useTicks) {
        float progress = useTicks / 20.0f;
        if (progress >= 0.90f) {
            return PullStage.PULLING_2;
        }
        if (progress >= 0.65f) {
            return PullStage.PULLING_1;
        }
        return PullStage.PULLING_0;
    }

    /**
     * Resolves the stage to render for the local player's bow in the given display context.
     */
    public static PullStage getRenderPullStage(ItemStack stack, ItemDisplayContext displayContext) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || stack == null || stack.isEmpty()) {
            return PullStage.IDLE;
        }

        LocalPlayer player = client.player;
        boolean isHeldStack = (stack == player.getMainHandItem() || stack == player.getOffhandItem());
        boolean isHandContext = displayContext == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
            || displayContext == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
            || displayContext == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
            || displayContext == ItemDisplayContext.THIRD_PERSON_LEFT_HAND;

        if (!isHandContext && !isHeldStack) {
            return PullStage.IDLE;
        }
        if (!player.isUsingItem()) {
            return PullStage.IDLE;
        }

        ItemStack useItem = player.getUseItem();
        boolean usingThisBow = useItem == stack
            || (isHandContext && BowSkinManager.isBow(useItem, null));
        if (!usingThisBow) {
            return PullStage.IDLE;
        }

        float partialTicks = client.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        return stageForUseTime(player.getTicksUsingItem() + partialTicks);
    }
}
