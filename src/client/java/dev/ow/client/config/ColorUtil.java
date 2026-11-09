package dev.ow.client.config;

/**
 * Allocation-free colour helpers shared by the projectile renderer and the GUI preview.
 * All colours are packed {@code 0xRRGGBB} ints unless stated otherwise.
 */
public final class ColorUtil {

    /** Minimum HSV value used when rendering, so very dark picks never vanish against caves / night. */
    private static final float MIN_RENDER_VALUE = 0.65f;

    private ColorUtil() {}

    public static int hsvToRgb(float h, float s, float v) {
        h = h - (float) Math.floor(h);
        float c = v * s;
        float hp = h * 6.0f;
        float x = c * (1.0f - Math.abs((hp % 2.0f) - 1.0f));
        float r;
        float g;
        float b;
        switch ((int) hp) {
            case 0 -> { r = c; g = x; b = 0; }
            case 1 -> { r = x; g = c; b = 0; }
            case 2 -> { r = 0; g = c; b = x; }
            case 3 -> { r = 0; g = x; b = c; }
            case 4 -> { r = x; g = 0; b = c; }
            default -> { r = c; g = 0; b = x; }
        }
        float m = v - c;
        int ri = Math.round((r + m) * 255.0f);
        int gi = Math.round((g + m) * 255.0f);
        int bi = Math.round((b + m) * 255.0f);
        return (ri << 16) | (gi << 8) | bi;
    }

    /** Writes {h, s, v} (each 0..1) into {@code out}. Hue is left untouched for greys / black. */
    public static void rgbToHsv(int rgb, float[] out) {
        float r = ((rgb >> 16) & 0xFF) / 255.0f;
        float g = ((rgb >> 8) & 0xFF) / 255.0f;
        float b = (rgb & 0xFF) / 255.0f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float d = max - min;
        out[2] = max;
        out[1] = max <= 0.0f ? 0.0f : d / max;
        if (d > 1.0E-5f) {
            float h;
            if (max == r) {
                h = ((g - b) / d) % 6.0f;
            } else if (max == g) {
                h = (b - r) / d + 2.0f;
            } else {
                h = (r - g) / d + 4.0f;
            }
            h /= 6.0f;
            if (h < 0.0f) {
                h += 1.0f;
            }
            out[0] = h;
        }
    }

    /** Linear blend of {@code rgb} toward white by {@code t} (0..1). */
    public static int lightenToward(int rgb, float t) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        r += Math.round((255 - r) * t);
        g += Math.round((255 - g) * t);
        b += Math.round((255 - b) * t);
        return (r << 16) | (g << 8) | b;
    }

    /**
     * Colour actually drawn for the projectile body: the user's colour, with brightness lifted to a minimum so
     * the bolt stays readable (it is rendered fullbright, so only the hue/value needs protecting).
     */
    public static int renderGlowColor(int rgb) {
        float[] hsv = new float[3];
        rgbToHsv(rgb, hsv);
        if (hsv[2] >= MIN_RENDER_VALUE) {
            return rgb;
        }
        if (hsv[1] < 0.02f) {
            // pure grey / black: lift to a light grey instead of inventing a hue
            int v = Math.round(MIN_RENDER_VALUE * 255.0f);
            return (v << 16) | (v << 8) | v;
        }
        return hsvToRgb(hsv[0], hsv[1], MIN_RENDER_VALUE);
    }
}
