package dev.meowy.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.meowy.client.MeowyClient;
import dev.ow.client.skin.BowRenderContext;
import dev.ow.client.skin.BowSkinManager;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemModelResolver.class)
public class ItemModelResolverMixin {

    @Inject(method = "updateForLiving", at = @At("HEAD"))
    private void ow$beforeUpdateForLiving(
        ItemStackRenderState renderState,
        ItemStack stack,
        ItemDisplayContext displayContext,
        LivingEntity entity,
        CallbackInfo ci
    ) {
        BowRenderContext.pushLiving(entity, displayContext);
    }

    @Inject(method = "updateForLiving", at = @At("RETURN"))
    private void ow$afterUpdateForLiving(
        ItemStackRenderState renderState,
        ItemStack stack,
        ItemDisplayContext displayContext,
        LivingEntity entity,
        CallbackInfo ci
    ) {
        BowRenderContext.pop();
    }

    @Inject(method = "updateForNonLiving", at = @At("HEAD"), require = 0)
    private void ow$beforeUpdateForNonLiving(
        ItemStackRenderState renderState,
        ItemStack stack,
        ItemDisplayContext displayContext,
        Entity entity,
        CallbackInfo ci
    ) {
        BowRenderContext.pushNonLiving();
    }

    @Inject(method = "updateForNonLiving", at = @At("RETURN"), require = 0)
    private void ow$afterUpdateForNonLiving(
        ItemStackRenderState renderState,
        ItemStack stack,
        ItemDisplayContext displayContext,
        Entity entity,
        CallbackInfo ci
    ) {
        BowRenderContext.pop();
    }

    @WrapOperation(
        method = "*",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/item/ItemStack;get(Lnet/minecraft/core/component/DataComponentType;)Ljava/lang/Object;"
        )
    )
    private Object ow$overrideLocalPlayerBowModel(
        ItemStack stack,
        DataComponentType<?> componentType,
        Operation<Object> original
    ) {
        Object value = original.call(stack, componentType);
        if (componentType == DataComponents.ITEM_MODEL) {
            try {
                Identifier originalId = (value instanceof Identifier id) ? id : null;
                Identifier overrideId = BowSkinManager.resolveBowModelOverride(stack, originalId);
                if (overrideId != null) {
                    return overrideId;
                }
            } catch (Throwable t) {
                // Never let a skin-resolution bug crash rendering; fall back to the vanilla model.
                MeowyClient.logOnce("ItemModelResolverMixin.override", t);
            }
        }
        return value;
    }
}
