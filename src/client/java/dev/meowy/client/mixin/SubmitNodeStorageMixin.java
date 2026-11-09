package dev.meowy.client.mixin;

import dev.meowy.client.render.RenderOpacityContext;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.Arrays;

/**
 * Intercepts SubmitNodeStorage and SubmitNodeCollection model and block-model submissions
 * while a Meowy player/cape/armor opacity context is active and scales the packed ARGB tint alpha in real time.
 */
@Mixin({SubmitNodeStorage.class, SubmitNodeCollection.class})
public class SubmitNodeStorageMixin {

    @ModifyVariable(
        method = "submitModel",
        at = @At("HEAD"),
        argsOnly = true,
        ordinal = 2
    )
    private int meowy$modifySubmittedModelTint(int tintedColor) {
        if (RenderOpacityContext.isActive()) {
            return RenderOpacityContext.applyAlphaToColor(tintedColor);
        }
        return tintedColor;
    }

    @ModifyVariable(
        method = "submitBlockModel",
        at = @At("HEAD"),
        argsOnly = true,
        require = 0
    )
    private int[] meowy$modifySubmittedBlockModelTintLayers(int[] tintLayers) {
        if (!RenderOpacityContext.isActive()) {
            return tintLayers;
        }
        int untintedAlphaColor = RenderOpacityContext.applyAlphaToColor(0xFFFFFFFF);
        if (tintLayers == null || tintLayers.length == 0) {
            return new int[] { untintedAlphaColor };
        }
        int[] copy = Arrays.copyOf(tintLayers, tintLayers.length + 1);
        for (int i = 0; i < tintLayers.length; i++) {
            copy[i] = RenderOpacityContext.applyAlphaToColor(tintLayers[i]);
        }
        copy[tintLayers.length] = untintedAlphaColor;
        return copy;
    }
}
