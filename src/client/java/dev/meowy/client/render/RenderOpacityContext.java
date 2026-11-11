package dev.meowy.client.render;

import net.minecraft.util.ARGB;

/**
 * Allocation-free thread-local render opacity and phase-order stack used during Minecraft 26.2's
 * SubmitNodeCollector phase for local player model, cape, and armor pieces.
 *
 * <p>Assigns deferred SubmitNodeStorage phase orders (10 = body, 11 = armor/head/wings, 12 = cape)
 * when opacity is < 100% so that order(0) features (such as maps in item frames, items in item frames,
 * and the player's own held weapons) finish rendering BEFORE the semi-transparent player/cape blends over them.
 */
public final class RenderOpacityContext {

    public static final int ORDER_BODY  = 10;
    public static final int ORDER_ARMOR = 11;
    public static final int ORDER_CAPE  = 12;

    private static final class AlphaStack {
        final float[] values = new float[16];
        final int[] orders = new int[16];
        int size = 0;
    }

    private static final ThreadLocal<AlphaStack> STACK = ThreadLocal.withInitial(AlphaStack::new);

    private RenderOpacityContext() {}

    public static void push(float alpha) {
        push(alpha, ORDER_BODY);
    }

    public static void push(float alpha, int phaseOrder) {
        AlphaStack s = STACK.get();
        if (s.size < s.values.length) {
            s.values[s.size] = Math.clamp(alpha, 0.0f, 1.0f);
            s.orders[s.size] = phaseOrder;
            s.size++;
        }
    }

    public static void pop() {
        AlphaStack s = STACK.get();
        if (s.size > 0) {
            s.size--;
        }
    }

    public static boolean isActive() {
        AlphaStack s = STACK.get();
        return s.size > 0 && s.values[s.size - 1] < 0.999f;
    }

    public static float getCurrentAlpha() {
        AlphaStack s = STACK.get();
        return s.size == 0 ? 1.0f : s.values[s.size - 1];
    }

    public static int getCurrentOrder() {
        AlphaStack s = STACK.get();
        return s.size == 0 ? 0 : s.orders[s.size - 1];
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
        return ARGB.color(newAlpha, ARGB.red(argbColor), ARGB.green(argbColor), ARGB.blue(argbColor));
    }
}
