package dev.meowy.client.mixin;

import dev.meowy.client.config.MeowyConfig;
import net.minecraft.client.CameraType;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Ensures Options#setCameraType never lands on THIRD_PERSON_FRONT when Disable Front Camera is active.
 */
@Mixin(Options.class)
public class OptionsMixin {

    @ModifyVariable(method = "setCameraType", at = @At("HEAD"), argsOnly = true)
    private CameraType meowy$preventFrontCamera(CameraType type) {
        if (MeowyConfig.get().disableFrontCamera && type == CameraType.THIRD_PERSON_FRONT) {
            return CameraType.FIRST_PERSON;
        }
        return type;
    }
}
