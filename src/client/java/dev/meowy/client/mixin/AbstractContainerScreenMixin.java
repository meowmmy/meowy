package dev.meowy.client.mixin;

import dev.ow.client.skin.BowRenderContext;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerScreen.class)
public class AbstractContainerScreenMixin {

    @Inject(method = "extractSlot", at = @At("HEAD"), require = 0)
    private void ow$beforeExtractSlot(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        BowRenderContext.pushContainerSlot(slot);
    }

    @Inject(method = "extractSlot", at = @At("RETURN"), require = 0)
    private void ow$afterExtractSlot(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        BowRenderContext.pop();
    }

    @Inject(method = "extractCarriedItem", at = @At("HEAD"), require = 0)
    private void ow$beforeExtractCarriedItem(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
        BowRenderContext.pushLocalGui();
    }

    @Inject(method = "extractCarriedItem", at = @At("RETURN"), require = 0)
    private void ow$afterExtractCarriedItem(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
        BowRenderContext.pop();
    }
}
