package dev.meowy.client.render;

/**
 * Duck interface injected onto EntityRenderState so render layers know whether
 * the extracted state belongs to the local client player (Minecraft.getInstance().player).
 */
public interface MeowyRenderStateExt {
    boolean meowy$isLocalPlayer();
    void meowy$setLocalPlayer(boolean isLocalPlayer);

    boolean meowy$isPreviewEntity();
    void meowy$setPreviewEntity(boolean isPreviewEntity);

    float meowy$getPreviewYawOffset();
    void meowy$setPreviewYawOffset(float yawOffset);

    float meowy$getPreviewPitch();
    void meowy$setPreviewPitch(float pitch);
}
