package dev.meowy.client.mixin;

import dev.meowy.client.config.MeowyConfig;
import net.minecraft.client.CameraType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Skips THIRD_PERSON_FRONT when "Disable Front Camera" is enabled in CAMERA settings,
 * cycling directly between FIRST_PERSON and THIRD_PERSON_BACK.
 */
@Mixin(CameraType.class)
public class CameraTypeMixin {

    @Inject(method = "cycle", at = @At("RETURN"), cancellable = true)
    private void meowy$skipFrontCameraOnCycle(CallbackInfoReturnable<CameraType> cir) {
        if (MeowyConfig.get().disableFrontCamera && cir.getReturnValue() == CameraType.THIRD_PERSON_FRONT) {
            cir.setReturnValue(CameraType.FIRST_PERSON);
        }
    }
}
