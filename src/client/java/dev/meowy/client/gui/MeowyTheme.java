package dev.meowy.client.gui;

import dev.meowy.client.MeowyClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.ARGB;

/**
 * Central color palette, alpha modulation, and pixel/sans-serif typography renderer
 * matching the Meowy specification and reference layout.
 */
public final class MeowyTheme {
    // Primary Colors
    public static final int MAIN_WINDOW_BG        = 0xFF171426;
    public static final int SIDEBAR_BG            = 0xFF131222;
    public static final int MAIN_SETTINGS_BG      = 0xFF1C1930;
    public static final int SETTINGS_ROW_BG       = 0xFF211E36;
    public static final int SECONDARY_BG          = 0xFF272340;
    public static final int PRIMARY_PURPLE_ACCENT = 0xFF8271FF;
    public static final int BRIGHT_LAVENDER       = 0xFFA99BFF;
    public static final int SECONDARY_BLUE_ACCENT = 0xFF668CFF;

    // Text Colors
    public static final int TEXT_PRIMARY          = 0xFFFFFFFF;
    public static final int TEXT_SECONDARY        = 0xFFC5C1D8;
    public static final int TEXT_MUTED            = 0xFF88839F;

    // Borders and Interactive Elements
    public static final int BORDER_COLOR          = 0xFF39344F;
    public static final int HOVER_BG              = 0xFF302A4C;
    public static final int SELECTED_CATEGORY_BG  = 0xFF302A50;

    private static final Style PIXEL_STYLE = Style.EMPTY.withFont(new FontDescription.Resource(MeowyClient.PIXEL_FONT_ID)).withBold(true);
    private static final Style SANS_STYLE  = Style.EMPTY.withFont(new FontDescription.Resource(MeowyClient.SANS_FONT_ID));

    private MeowyTheme() {}

    public static int withAlpha(int argb, float alphaFactor) {
        int baseAlpha = (argb >>> 24) & 0xFF;
        int scaledAlpha = Math.clamp(Math.round(baseAlpha * Math.clamp(alphaFactor, 0.0f, 1.0f)), 0, 255);
        return (argb & 0x00FFFFFF) | (scaledAlpha << 24);
    }

    public static int lerpColor(int colorA, int colorB, float t) {
        float clamped = Math.clamp(t, 0.0f, 1.0f);
        int a = Math.round(ARGB.alpha(colorA) + (ARGB.alpha(colorB) - ARGB.alpha(colorA)) * clamped);
        int r = Math.round(ARGB.red(colorA) + (ARGB.red(colorB) - ARGB.red(colorA)) * clamped);
        int g = Math.round(ARGB.green(colorA) + (ARGB.green(colorB) - ARGB.green(colorA)) * clamped);
        int b = Math.round(ARGB.blue(colorA) + (ARGB.blue(colorB) - ARGB.blue(colorA)) * clamped);
        return ARGB.color(a, r, g, b);
    }

    public static Component pixelText(String raw, boolean wideTracking) {
        String formatted = wideTracking ? spaceLetters(raw) : raw;
        MutableComponent comp = Component.literal(formatted);
        comp.withStyle(PIXEL_STYLE);
        return comp;
    }

    public static Component sansText(String raw) {
        MutableComponent comp = Component.literal(raw);
        comp.withStyle(SANS_STYLE);
        return comp;
    }

    public static void drawSoftPanel(
        GuiGraphicsExtractor graphics,
        int x,
        int y,
        int width,
        int height,
        int bgColor,
        int borderColor
    ) {
        if (width <= 2 || height <= 2) return;
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, bgColor);
        graphics.fill(x + 1, y, x + width - 1, y + 1, borderColor);
        graphics.fill(x + 1, y + height - 1, x + width - 1, y + height, borderColor);
        graphics.fill(x, y + 1, x + 1, y + height - 1, borderColor);
        graphics.fill(x + width - 1, y + 1, x + width, y + height - 1, borderColor);
    }

    private static String spaceLetters(String text) {
        if (text == null || text.length() <= 1) return text;
        StringBuilder sb = new StringBuilder(text.length() * 2);
        for (int i = 0; i < text.length(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(text.charAt(i));
        }
        return sb.toString();
    }
}
