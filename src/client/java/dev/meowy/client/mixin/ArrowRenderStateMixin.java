package dev.meowy.client.mixin;

import dev.ow.client.render.LaserBulletRenderer.LaserBulletState;
import dev.ow.client.render.OwArrowRenderStateExt;
import net.minecraft.client.renderer.entity.state.ArrowRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ArrowRenderState.class)
public class ArrowRenderStateMixin implements OwArrowRenderStateExt {

    @Unique
    private boolean ow$localPlayerArrow = false;

    @Unique
    private LaserBulletState ow$bulletState = null;

    @Override
    public boolean ow$isLocalPlayerArrow() {
        return this.ow$localPlayerArrow;
    }

    @Override
    public void ow$setLocalPlayerArrow(boolean isLocal) {
        this.ow$localPlayerArrow = isLocal;
    }

    @Override
    public LaserBulletState ow$getBulletState() {
        return this.ow$bulletState;
    }

    @Override
    public void ow$setBulletState(LaserBulletState state) {
        this.ow$bulletState = state;
    }
}
