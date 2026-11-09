package dev.meowy.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.meowy.client.config.MeowyConfig;
import dev.meowy.client.render.CrosshairProjectionHelper;
import net.minecraft.client.CameraType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.renderer.RenderPipelines;
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
 * Implements CAMERA category features on Minecraft 26.2's Hud#extractCrosshair:
 * 1. Third-Person Crosshair visibility toggle (always uses Accurate 3D raycast projection onto screen).
 * 2. Dynamic Entity Target Crosshair Color with custom Hex tinting (works at any range when holding a bow).
 */
@Mixin(Hud.class)
public class HudCrosshairMixin {

    @Shadow
    @Final
    private Minecraft minecraft;

    @Unique
    private boolean meowy$isTargetHovered = false;

    @Unique
    private int meowy$offsetX = 0;

    @Unique
    private int meowy$offsetY = 0;

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
        this.meowy$isTargetHovered = false;
        this.meowy$offsetX = 0;
        this.meowy$offsetY = 0;

        MeowyConfig cfg = MeowyConfig.get();
        float partialTicks = deltaTracker.getGameTimeDeltaPartialTick(true);

        if (cfg.dynamicCrosshairColorEnabled) {
            this.meowy$isTargetHovered = CrosshairProjectionHelper.isHoveringTargetEntity(this.minecraft, partialTicks);
        }

        // Third-Person Crosshair is always accurate by default when enabled
        if (cfg.f5CrosshairEnabled && !this.minecraft.options.getCameraType().isFirstPerson()) {
            Vector3f offset = CrosshairProjectionHelper.computeAccurateF5Offset(
                this.minecraft,
                partialTicks,
                graphics.guiWidth(),
                graphics.guiHeight()
            );
            this.meowy$offsetX = Math.round(offset.x);
            this.meowy$offsetY = Math.round(offset.y);
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
    private void meowy$offsetAndRecolorCrosshairSprite(
        GuiGraphicsExtractor graphics,
        RenderPipeline pipeline,
        Identifier sprite,
        int x,
        int y,
        int width,
        int height,
        Operation<Void> original
    ) {
        int drawX = x + this.meowy$offsetX;
        int drawY = y + this.meowy$offsetY;
        if (this.meowy$isTargetHovered && MeowyConfig.get().dynamicCrosshairColorEnabled) {
            int argb = MeowyConfig.get().getTargetCrosshairArgb();
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, drawX, drawY, width, height, argb);
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
    private void meowy$offsetAttackIndicatorProgressSprite(
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
        int drawX = x + this.meowy$offsetX;
        int drawY = y + this.meowy$offsetY;
        original.call(graphics, pipeline, sprite, texW, texH, u, v, drawX, drawY, width, height);
    }

    @Inject(method = "extractCrosshair", at = @At("RETURN"))
    private void meowy$endCrosshairExtract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        this.meowy$offsetX = 0;
        this.meowy$offsetY = 0;
    }
}
