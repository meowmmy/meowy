package dev.meowy.client.mixin;

import dev.ow.client.skin.BowRenderContext;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
public class HudMixin {

    @Inject(method = "extractSlot", at = @At("HEAD"), require = 0)
    private void ow$beforeExtractHotbarSlot(
        GuiGraphicsExtractor graphics,
        int x,
        int y,
        DeltaTracker deltaTracker,
        Player player,
        ItemStack stack,
        int seed,
        CallbackInfo ci
    ) {
        BowRenderContext.pushLiving(player, ItemDisplayContext.GUI);
    }

    @Inject(method = "extractSlot", at = @At("RETURN"), require = 0)
    private void ow$afterExtractHotbarSlot(
        GuiGraphicsExtractor graphics,
        int x,
        int y,
        DeltaTracker deltaTracker,
        Player player,
        ItemStack stack,
        int seed,
        CallbackInfo ci
    ) {
        BowRenderContext.pop();
    }
}
