package dev.meowy.client.render;

import net.minecraft.util.ARGB;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Thread-local render opacity stack used during Minecraft 26.2's SubmitNodeCollector phase
 * for local player model, cape, and armor pieces.
 */
public final class RenderOpacityContext {
    public enum Target {
        NONE,
        PLAYER_BODY,
        CAPE,
        HELMET,
        CHESTPLATE,
        LEGGINGS,
        BOOTS
    }

    public record Frame(Target target, float alpha) {}

    private static final ThreadLocal<Deque<Frame>> STACK = ThreadLocal.withInitial(ArrayDeque::new);

    private RenderOpacityContext() {}

    public static void push(Target target, float alpha) {
        STACK.get().push(new Frame(target, Math.clamp(alpha, 0.0f, 1.0f)));
    }

    public static void pop() {
        Deque<Frame> deque = STACK.get();
        if (!deque.isEmpty()) {
            deque.pop();
        }
    }

    public static boolean isActive() {
        Deque<Frame> deque = STACK.get();
        return !deque.isEmpty() && deque.peek().alpha() < 0.999f;
    }

    public static float getCurrentAlpha() {
        Deque<Frame> deque = STACK.get();
        return deque.isEmpty() ? 1.0f : deque.peek().alpha();
    }

    public static Target getCurrentTarget() {
        Deque<Frame> deque = STACK.get();
        return deque.isEmpty() ? Target.NONE : deque.peek().target();
    }

    public static int applyAlphaToColor(int argbColor) {
        float alphaMultiplier = getCurrentAlpha();
        if (alphaMultiplier >= 0.999f) {
            return argbColor;
        }
        int origAlpha = (argbColor >>> 24) & 0xFF;
        if (origAlpha == 0) {
            origAlpha = 255;
        }
        int newAlpha = Math.clamp(Math.round(origAlpha * alphaMultiplier), 0, 255);
        int red = ARGB.red(argbColor);
        int green = ARGB.green(argbColor);
        int blue = ARGB.blue(argbColor);
        return ARGB.color(newAlpha, red, green, blue);
    }
}
