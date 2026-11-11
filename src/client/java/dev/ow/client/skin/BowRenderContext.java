package dev.ow.client.skin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Allocation-free thread-local context tracker ensuring that bow skin overrides apply strictly to
 * the local player's hands, third-person model, hotbar, and inventory slots.
 */
public final class BowRenderContext {

    public enum OwnerScope {
        UNKNOWN,
        LOCAL_PLAYER_LIVING,
        OTHER_ENTITY,
        LOCAL_PLAYER_GUI
    }

    private static final class ContextStack {
        final OwnerScope[] scopes = new OwnerScope[16];
        final ItemDisplayContext[] contexts = new ItemDisplayContext[16];
        int size = 0;

        void push(OwnerScope scope, ItemDisplayContext ctx) {
            if (this.size < this.scopes.length) {
                this.scopes[this.size] = scope;
                this.contexts[this.size] = ctx;
                this.size++;
            }
        }

        void pop() {
            if (this.size > 0) {
                this.size--;
            }
        }
    }

    private static final ThreadLocal<ContextStack> STACK = ThreadLocal.withInitial(ContextStack::new);

    private BowRenderContext() {}

    public static void pushLiving(LivingEntity entity, ItemDisplayContext displayContext) {
        Minecraft client = Minecraft.getInstance();
        boolean isLocal = (client != null && client.player != null && entity == client.player);
        OwnerScope scope = isLocal ? OwnerScope.LOCAL_PLAYER_LIVING : OwnerScope.OTHER_ENTITY;
        STACK.get().push(scope, displayContext != null ? displayContext : ItemDisplayContext.NONE);
    }

    public static void pushNonLiving() {
        STACK.get().push(OwnerScope.OTHER_ENTITY, ItemDisplayContext.NONE);
    }

    public static void pushContainerSlot(Slot slot) {
        Minecraft client = Minecraft.getInstance();
        boolean isLocalInventorySlot = false;
        if (client != null && client.player != null && slot != null) {
            if (slot.container == client.player.getInventory() || client.gui.screen() instanceof InventoryScreen) {
                isLocalInventorySlot = true;
            }
        }
        STACK.get().push(isLocalInventorySlot ? OwnerScope.LOCAL_PLAYER_GUI : OwnerScope.OTHER_ENTITY, ItemDisplayContext.GUI);
    }

    public static void pushLocalGui() {
        STACK.get().push(OwnerScope.LOCAL_PLAYER_GUI, ItemDisplayContext.GUI);
    }

    public static void pop() {
        STACK.get().pop();
    }

    public static ItemDisplayContext currentDisplayContext() {
        ContextStack s = STACK.get();
        return s.size > 0 ? s.contexts[s.size - 1] : ItemDisplayContext.GUI;
    }

    public static boolean shouldOverrideForLocalPlayer(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        ContextStack s = STACK.get();
        if (s.size > 0) {
            return switch (s.scopes[s.size - 1]) {
                case LOCAL_PLAYER_LIVING, LOCAL_PLAYER_GUI -> true;
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
