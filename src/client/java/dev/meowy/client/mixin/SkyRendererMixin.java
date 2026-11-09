package dev.meowy.client.mixin;

import dev.meowy.client.config.MeowyConfig;
import dev.meowy.client.skybox.CustomSkyboxManager;
import dev.meowy.client.skybox.CustomSkyboxRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SkyRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks into Minecraft 26.2's SkyRenderer to render OptiFine / MCPatcher custom skyboxes
 * with full Sodium compatibility.
 */
@Mixin(SkyRenderer.class)
public class SkyRendererMixin {

    @Inject(method = "renderSkyDisc", at = @At("RETURN"), require = 0)
    private void meowy$renderCustomSkyAfterSkyDisc(int skyColor, CallbackInfo ci) {
        if (MeowyConfig.get().customSkyboxEnabled && CustomSkyboxManager.getInstance().hasActiveSkyboxes()) {
            Minecraft client = Minecraft.getInstance();
            float partialTicks = client.getDeltaTracker().getGameTimeDeltaPartialTick(true);
            Matrix4f identity = new Matrix4f();
            CustomSkyboxRenderer.renderCustomSkyboxes(identity, partialTicks);
        }
    }
}
