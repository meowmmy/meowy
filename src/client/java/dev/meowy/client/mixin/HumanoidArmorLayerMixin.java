package dev.meowy.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.meowy.client.config.MeowyConfig;
import dev.meowy.client.render.MeowyRenderStateExt;
import dev.meowy.client.render.RenderOpacityContext;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Controls per-slot armor opacity (Helmet, Chestplate, Leggings, Boots) including trims and glint
 * strictly for the local player.
 */
@Mixin(HumanoidArmorLayer.class)
public class HumanoidArmorLayerMixin<S extends HumanoidRenderState> {

    @Inject(method = "renderArmorPiece", at = @At("HEAD"), cancellable = true)
    private void meowy$beforeArmorPiece(PoseStack poseStack, SubmitNodeCollector collector, ItemStack itemStack, EquipmentSlot slot, int lightCoords, S state, CallbackInfo ci) {
        if (!((MeowyRenderStateExt) state).meowy$isLocalPlayer()) {
            return;
        }
        float slotOpacity = meowy$getSlotOpacity(slot);
        if (slotOpacity <= 0.001f) {
            ci.cancel();
            return;
        }
        RenderOpacityContext.push(meowy$getTargetForSlot(slot), slotOpacity);
    }

    @Inject(method = "renderArmorPiece", at = @At("RETURN"))
    private void meowy$afterArmorPiece(PoseStack poseStack, SubmitNodeCollector collector, ItemStack itemStack, EquipmentSlot slot, int lightCoords, S state, CallbackInfo ci) {
        if (((MeowyRenderStateExt) state).meowy$isLocalPlayer() && meowy$getSlotOpacity(slot) > 0.001f) {
            RenderOpacityContext.pop();
        }
    }

    @Unique
    private static float meowy$getSlotOpacity(EquipmentSlot slot) {
        MeowyConfig cfg = MeowyConfig.get();
        return switch (slot) {
            case HEAD -> cfg.helmetOpacity;
            case CHEST -> cfg.chestplateOpacity;
            case LEGS -> cfg.leggingsOpacity;
            case FEET -> cfg.bootsOpacity;
            default -> 1.0f;
        };
    }

    @Unique
    private static RenderOpacityContext.Target meowy$getTargetForSlot(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> RenderOpacityContext.Target.HELMET;
            case CHEST -> RenderOpacityContext.Target.CHESTPLATE;
            case LEGS -> RenderOpacityContext.Target.LEGGINGS;
            case FEET -> RenderOpacityContext.Target.BOOTS;
            default -> RenderOpacityContext.Target.NONE;
        };
    }
}
