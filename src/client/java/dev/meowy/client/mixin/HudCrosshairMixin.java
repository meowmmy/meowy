package dev.meowy.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.meowy.client.config.MeowyConfig;
import dev.meowy.client.render.CrosshairProjectionHelper;
import dev.meowy.client.render.CustomCrosshairRenderer;
import dev.meowy.client.render.HitmarkerManager;
import net.minecraft.client.CameraType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.resources.Identifier;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Implements CAMERA & MISC crosshair features on Minecraft 26.2's Hud#extractCrosshair:
 * 1. Third-Person Crosshair visibility toggle (always uses Accurate 3D raycast projection onto screen).
 * 2. Meowy Crosshair (completely hides the default Minecraft crosshair & replaces it with CrosshairWithoutHitmarker.png).
 * 3. Crosshair Scale (scales both Meowy Crosshair / Hitmarkers and the default crosshair around the crosshair center).
 * 4. Overwatch Hitmarkers (drawn from CrosshairWithHitmarker.png before offsets reset so it follows the F5 crosshair offset).
 */
@Mixin(Hud.class)
public class HudCrosshairMixin {

    @Shadow
    @Final
    private Minecraft minecraft;

    @Unique
    private float meowy$offsetX = 0.0f;

    @Unique
    private float meowy$offsetY = 0.0f;

    @Unique
    private boolean meowy$crosshairActiveThisFrame = false;

    @WrapOperation(
        method = "extractCrosshair",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/CameraType;isFirstPerson()Z")
    )
    private boolean meowy$allowThirdPersonCrosshair(CameraType cameraType, Operation<Boolean> original) {
        if (MeowyConfig.get().f5CrosshairEnabled) {
            return true;
        }
        return original.call(cameraType);
    }

    @Inject(method = "extractCrosshair", at = @At("HEAD"))
    private void meowy$beginCrosshairExtract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        this.meowy$offsetX = 0.0f;
        this.meowy$offsetY = 0.0f;
        this.meowy$crosshairActiveThisFrame = false;

        MeowyConfig cfg = MeowyConfig.get();
        float partialTicks = deltaTracker.getGameTimeDeltaPartialTick(true);

        if (cfg.f5CrosshairEnabled && !this.minecraft.options.getCameraType().isFirstPerson()) {
            Vector3f offset = CrosshairProjectionHelper.computeAccurateF5Offset(
                this.minecraft,
                partialTicks,
                graphics.guiWidth(),
                graphics.guiHeight()
            );
            this.meowy$offsetX = offset.x;
            this.meowy$offsetY = offset.y;
        }
    }

    @WrapOperation(
        method = "extractCrosshair",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"
        ),
        require = 0
    )
    private void meowy$handleMainCrosshairSprite(
        GuiGraphicsExtractor graphics,
        RenderPipeline pipeline,
        Identifier sprite,
        int x,
        int y,
        int width,
        int height,
        Operation<Void> original
    ) {
        boolean isMainCrosshairSprite = sprite != null && "hud/crosshair".equals(sprite.getPath());
        if (isMainCrosshairSprite) {
            this.meowy$crosshairActiveThisFrame = true;
            if (MeowyConfig.get().meowyCrosshairEnabled) {
                // Completely hide the default Minecraft crosshair
                return;
            }
        } else if (MeowyConfig.get().meowyCrosshairEnabled
            && sprite != null
            && sprite.getPath().startsWith("hud/crosshair")) {
            // Also hide default crosshair sub-sprites when Meowy Crosshair is enabled
            return;
        }

        int drawX = x + Math.round(this.meowy$offsetX);
        int drawY = y + Math.round(this.meowy$offsetY);
        float scale = MeowyConfig.get().crosshairScale;
        if (isMainCrosshairSprite && Math.abs(scale - 1.0f) > 0.01f) {
            float cx = drawX + width * 0.5f;
            float cy = drawY + height * 0.5f;
            graphics.pose().pushMatrix();
            graphics.pose().translate(cx, cy);
            graphics.pose().scale(scale, scale);
            graphics.pose().translate(-cx, -cy);
            original.call(graphics, pipeline, sprite, drawX, drawY, width, height);
            graphics.pose().popMatrix();
            return;
        }
        original.call(graphics, pipeline, sprite, drawX, drawY, width, height);
    }

    @WrapOperation(
        method = "extractCrosshair",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIIIIII)V"
        ),
        require = 0
    )
    private void meowy$handleAttackIndicatorProgressSprite(
        GuiGraphicsExtractor graphics,
        RenderPipeline pipeline,
        Identifier sprite,
        int texW,
        int texH,
        int u,
        int v,
        int x,
        int y,
        int width,
        int height,
        Operation<Void> original
    ) {
        if (MeowyConfig.get().meowyCrosshairEnabled
            && sprite != null
            && sprite.getPath().startsWith("hud/crosshair")) {
            return;
        }
        int drawX = x + Math.round(this.meowy$offsetX);
        int drawY = y + Math.round(this.meowy$offsetY);
        original.call(graphics, pipeline, sprite, texW, texH, u, v, drawX, drawY, width, height);
    }

    @Inject(method = "extractCrosshair", at = @At("RETURN"))
    private void meowy$endCrosshairExtract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        float centerX = (graphics.guiWidth() * 0.5f) + this.meowy$offsetX;
        float centerY = (graphics.guiHeight() * 0.5f) + this.meowy$offsetY;

        if (this.meowy$crosshairActiveThisFrame) {
            if (MeowyConfig.get().hitmarkersEnabled) {
                HitmarkerManager.render(graphics, centerX, centerY);
            }
            if (MeowyConfig.get().meowyCrosshairEnabled) {
                CustomCrosshairRenderer.render(graphics, centerX, centerY);
            }
        }

        this.meowy$offsetX = 0.0f;
        this.meowy$offsetY = 0.0f;
        this.meowy$crosshairActiveThisFrame = false;
    }
}
