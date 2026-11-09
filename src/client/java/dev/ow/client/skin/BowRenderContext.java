package dev.ow.client.skin;

import dev.ow.client.animation.BowPullTracker.PullStage;
import dev.ow.client.util.ScreenAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Thread-local context tracker ensuring that bow skin overrides apply strictly to:
 * <ul>
 *   <li>First-person hand rendering of the local player</li>
 *   <li>Third-person rendering of the local player</li>
 *   <li>Hotbar and inventory slots belonging to the local player</li>
 *   <li>The live preview inside the {@code /ow} GUI</li>
 * </ul>
 * And NEVER to other players' bows, dropped world items, item frames, or armor stands.
 */
public final class BowRenderContext {

    public enum OwnerScope {
        UNKNOWN,
        LOCAL_PLAYER_LIVING,
        OTHER_ENTITY,
        LOCAL_PLAYER_GUI,
        OW_GUI_PREVIEW
    }

    public record Frame(OwnerScope scope, ItemDisplayContext displayContext, PullStage previewStage) {}

    private static final ThreadLocal<Deque<Frame>> STACK = ThreadLocal.withInitial(ArrayDeque::new);

    private BowRenderContext() {}

    public static void pushLiving(LivingEntity entity, ItemDisplayContext displayContext) {
        Minecraft client = Minecraft.getInstance();
        boolean isLocal = (client != null && client.player != null && entity == client.player);
        OwnerScope scope = isLocal ? OwnerScope.LOCAL_PLAYER_LIVING : OwnerScope.OTHER_ENTITY;
        STACK.get().push(new Frame(scope, displayContext != null ? displayContext : ItemDisplayContext.NONE, null));
    }

    public static void pushNonLiving() {
        STACK.get().push(new Frame(OwnerScope.OTHER_ENTITY, ItemDisplayContext.NONE, null));
    }

    public static void pushContainerSlot(Slot slot) {
        Minecraft client = Minecraft.getInstance();
        boolean isLocalInventorySlot = false;
        if (client != null && client.player != null && slot != null) {
            if (slot.container == client.player.getInventory()) {
                isLocalInventorySlot = true;
            } else if (ScreenAccess.current(client) instanceof InventoryScreen) {
                isLocalInventorySlot = true;
            }
        }
        OwnerScope scope = isLocalInventorySlot ? OwnerScope.LOCAL_PLAYER_GUI : OwnerScope.OTHER_ENTITY;
        STACK.get().push(new Frame(scope, ItemDisplayContext.GUI, null));
    }

    public static void pushLocalGui() {
        STACK.get().push(new Frame(OwnerScope.LOCAL_PLAYER_GUI, ItemDisplayContext.GUI, null));
    }

    public static void pushOwPreview(PullStage stage) {
        STACK.get().push(new Frame(OwnerScope.OW_GUI_PREVIEW, ItemDisplayContext.GUI, stage != null ? stage : PullStage.IDLE));
    }

    public static void pop() {
        Deque<Frame> deque = STACK.get();
        if (!deque.isEmpty()) {
            deque.pop();
        }
    }

    public static Frame current() {
        return STACK.get().peek();
    }

    public static boolean shouldOverrideForLocalPlayer(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        Frame frame = STACK.get().peek();
        if (frame != null) {
            return switch (frame.scope()) {
                case LOCAL_PLAYER_LIVING, LOCAL_PLAYER_GUI, OW_GUI_PREVIEW -> true;
                case OTHER_ENTITY -> false;
                case UNKNOWN -> isStackOwnedByLocalPlayer(stack);
            };
        }
        return isStackOwnedByLocalPlayer(stack);
    }

    private static boolean isStackOwnedByLocalPlayer(ItemStack stack) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) {
            return false;
        }
        if (stack == client.player.getMainHandItem() || stack == client.player.getOffhandItem()) {
            return true;
        }
        var inv = client.player.getInventory();
        int size = inv.getContainerSize();
        for (int i = 0; i < size; i++) {
            if (inv.getItem(i) == stack) {
                return true;
            }
        }
        return false;
    }
}
