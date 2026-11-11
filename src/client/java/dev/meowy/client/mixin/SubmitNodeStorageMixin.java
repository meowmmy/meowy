package dev.meowy.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.meowy.client.render.MeowyPreviewContext;
import dev.meowy.client.render.RenderOpacityContext;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.Arrays;

/**
 * Intercepts SubmitNodeStorage and SubmitNodeCollection model and block-model submissions
 * while a Meowy player/cape/armor opacity context is active:
 * 1. Scales the packed ARGB tint alpha in real time.
 * 2. Redirects semi-transparent world player/armor/cape submissions from order(0) into deferred
 *    phases order(10..12) so that order(0) features (maps in item frames, items in item frames,
 *    and held weapons) finish drawing before the semi-transparent player/cape blends over them.
 */
@Mixin({SubmitNodeStorage.class, SubmitNodeCollection.class})
public class SubmitNodeStorageMixin {

    @WrapOperation(
        method = "submitModel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/SubmitNodeStorage;order(I)Lnet/minecraft/client/renderer/SubmitNodeCollection;"
        ),
        require = 0
    )
    private SubmitNodeCollection meowy$deferTranslucentPlayerPhaseOrder(
        SubmitNodeStorage storage,
        int originalOrder,
        Operation<SubmitNodeCollection> original
    ) {
        if (originalOrder == 0 && RenderOpacityContext.isActive() && !MeowyPreviewContext.isRenderingPreview()) {
            return original.call(storage, RenderOpacityContext.getCurrentOrder());
        }
        return original.call(storage, originalOrder);
    }

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
