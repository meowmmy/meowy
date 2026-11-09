package dev.meowy.client.mixin;

import dev.meowy.client.render.MeowyRenderStateExt;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(EntityRenderState.class)
public class EntityRenderStateMixin implements MeowyRenderStateExt {
    @Unique
    private boolean meowy$isLocalPlayer = false;

    @Unique
    private boolean meowy$isPreviewEntity = false;

    @Unique
    private float meowy$previewYawOffset = 0.0f;

    @Unique
    private float meowy$previewPitch = 0.0f;

    @Override
    public boolean meowy$isLocalPlayer() {
        return this.meowy$isLocalPlayer;
    }

    @Override
    public void meowy$setLocalPlayer(boolean isLocalPlayer) {
        this.meowy$isLocalPlayer = isLocalPlayer;
    }

    @Override
    public boolean meowy$isPreviewEntity() {
        return this.meowy$isPreviewEntity;
    }

    @Override
    public void meowy$setPreviewEntity(boolean isPreviewEntity) {
        this.meowy$isPreviewEntity = isPreviewEntity;
    }

    @Override
    public float meowy$getPreviewYawOffset() {
        return this.meowy$previewYawOffset;
    }

    @Override
    public void meowy$setPreviewYawOffset(float yawOffset) {
        this.meowy$previewYawOffset = yawOffset;
    }

    @Override
    public float meowy$getPreviewPitch() {
        return this.meowy$previewPitch;
    }

    @Override
    public void meowy$setPreviewPitch(float pitch) {
        this.meowy$previewPitch = pitch;
    }
}
