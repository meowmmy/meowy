package dev.meowy.client.render;

import dev.meowy.client.config.MeowyConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Renders the 'Meowy Crosshair' using the exact pixel data from CrosshairWithoutHitmarker.png,
 * supporting user-configurable Crosshair Scale and Third-Person Crosshair offsets.
 */
public final class CustomCrosshairRenderer {

    /** Base scale mapping the 300x300 reference asset's 6x6 dot to crisp GUI coordinates at 1.00x scale. */
    public static final float BASE_ASSET_SCALE = 0.5f;

    /**
     * Exact horizontal pixel spans {dx0, dy0, dx1, dy1, argb} extracted from CrosshairWithoutHitmarker.png
     * relative to center (150, 150).
     */
    private static final int[][] CROSSHAIR_SPANS = {
        {-3, -3, -2, -2, 0x18000000},
        {-2, -3, -1, -2, 0xA2000000},
        {-1, -3, 1, -2, 0xE8000000},
        {1, -3, 2, -2, 0xA2000000},
        {2, -3, 3, -2, 0x18000000},
        {-3, -2, -2, -1, 0xA7000000},
        {-2, -2, -1, -1, 0xFF565656},
        {-1, -2, 1, -1, 0xFFD8D8D8},
        {1, -2, 2, -1, 0xFF565656},
        {2, -2, 3, -1, 0xA7000000},
        {-3, -1, -2, 0, 0xF0000000},
        {-2, -1, -1, 0, 0xFFD8D8D8},
        {-1, -1, 1, 0, 0xFFFFFFFF},
        {1, -1, 2, 0, 0xFFD8D8D8},
        {2, -1, 3, 0, 0xF0000000},
        {-3, 0, -2, 1, 0xF0000000},
        {-2, 0, -1, 1, 0xFFD8D8D8},
        {-1, 0, 1, 1, 0xFFFFFFFF},
        {1, 0, 2, 1, 0xFFD8D8D8},
        {2, 0, 3, 1, 0xF0000000},
        {-3, 1, -2, 2, 0xA7000000},
        {-2, 1, -1, 2, 0xFF565656},
        {-1, 1, 1, 2, 0xFFD8D8D8},
        {1, 1, 2, 2, 0xFF565656},
        {2, 1, 3, 2, 0xA7000000},
        {-3, 2, -2, 3, 0x18000000},
        {-2, 2, -1, 3, 0xA2000000},
        {-1, 2, 1, 3, 0xE8000000},
        {1, 2, 2, 3, 0xA2000000},
        {2, 2, 3, 3, 0x18000000}
    };

    private CustomCrosshairRenderer() {}

    public static void render(GuiGraphicsExtractor graphics, float centerX, float centerY) {
        float scale = Math.max(0.1f, MeowyConfig.get().crosshairScale) * BASE_ASSET_SCALE;

        graphics.pose().pushMatrix();
        graphics.pose().translate(centerX, centerY);
        graphics.pose().scale(scale, scale);

        for (int i = 0; i < CROSSHAIR_SPANS.length; i++) {
            int[] span = CROSSHAIR_SPANS[i];
            graphics.fill(span[0], span[1], span[2], span[3], span[4]);
        }

        graphics.pose().popMatrix();
    }
}
