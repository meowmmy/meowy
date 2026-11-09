package dev.meowy.client.mixin;

import dev.meowy.client.config.MeowyConfig;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.state.LightmapRenderState;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Implements the Fullbright toggle in VISUAL settings by maximizing LightmapRenderState
 * brightness and ambient light during lightmap updates and returning 1.0f from getBrightness.
 */
@Mixin(Lightmap.class)
public class LightmapMixin {

    @Unique
    private boolean meowy$wasFullbright = false;

    @Inject(method = "render", at = @At("HEAD"))
    private void meowy$applyFullbrightToLightmapState(LightmapRenderState renderState, CallbackInfo ci) {
        boolean enabled = MeowyConfig.get().fullbrightEnabled;
        if (enabled) {
            renderState.needsUpdate = true;
            renderState.brightness = 16.0f;
            renderState.skyFactor = 1.0f;
            renderState.blockFactor = 1.0f;
            renderState.darknessEffectScale = 0.0f;
            renderState.nightVisionEffectIntensity = 1.0f;
            this.meowy$wasFullbright = true;
        } else if (this.meowy$wasFullbright) {
            renderState.needsUpdate = true;
            this.meowy$wasFullbright = false;
        }
    }

    @Inject(method = "getBrightness", at = @At("HEAD"), cancellable = true)
    private static void meowy$fullbrightBrightness(DimensionType dimensionType, int level, CallbackInfoReturnable<Float> cir) {
        if (MeowyConfig.get().fullbrightEnabled) {
            cir.setReturnValue(1.0f);
        }
    }
}
