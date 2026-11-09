package dev.ow.client.render;

import dev.ow.client.render.LaserBulletRenderer.LaserBulletState;

/**
 * Duck interface injected onto {@code net.minecraft.client.renderer.entity.state.ArrowRenderState}
 * so {@code ArrowRendererMixin} knows whether the extracted arrow belongs to the local player
 * and holds its extracted {@link LaserBulletState} for submission.
 */
public interface OwArrowRenderStateExt {
    boolean ow$isLocalPlayerArrow();

    void ow$setLocalPlayerArrow(boolean isLocal);

    LaserBulletState ow$getBulletState();

    void ow$setBulletState(LaserBulletState state);
}
