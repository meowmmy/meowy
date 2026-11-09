package dev.ow.client.util;

import dev.meowy.client.MeowyClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Version-tolerant access to "the current screen" and "open a screen".
 *
 * <p>Minecraft 26.2 moved the current screen off {@code Minecraft} and onto {@code Gui}
 * ({@code Minecraft.gui.screen()} / {@code Minecraft.gui.setScreen(..)}), while the heads-up display moved to
 * {@code Hud}. A direct {@code client.gui.screen()} call bakes the <em>declared type</em> of the {@code gui}
 * field into the compiled class, so if the jar is ever compiled against a slightly different Minecraft build than
 * the one it runs on, it dies with {@code NoSuchFieldError: ... 'Hud gui'}.
 *
 * <p>This helper looks everything up by <em>name</em> once at runtime, so it never depends on the field's declared
 * type, and falls back to the pre-26.2 {@code Minecraft.screen} / {@code Minecraft.setScreen} API. If nothing can be
 * resolved it degrades to "no screen" and logs once instead of crashing the game.
 */
public final class ScreenAccess {

    private static boolean initialised = false;

    /** {@code Minecraft#gui} (26.2+). */
    private static Field guiField;
    /** {@code Gui#screen()} and {@code Gui#setScreen(Screen)} resolved against the live gui object's class. */
    private static Class<?> guiClass;
    private static Method guiScreenGetter;
    private static Method guiScreenSetter;

    /** {@code Minecraft#screen} / {@code Minecraft#setScreen(Screen)} (pre-26.2 fallback). */
    private static Field legacyScreenField;
    private static Method legacyScreenSetter;

    private ScreenAccess() {}

    /** Returns the screen currently open, or {@code null} when none is open (or it cannot be determined). */
    public static Screen current(Minecraft client) {
        if (client == null) {
            return null;
        }
        try {
            init();
            Object gui = guiField != null ? guiField.get(client) : null;
            if (gui != null) {
                resolveGuiMethods(gui);
                if (guiScreenGetter != null) {
                    Object screen = guiScreenGetter.invoke(gui);
                    return screen instanceof Screen s ? s : null;
                }
            }
            if (legacyScreenField != null) {
                Object screen = legacyScreenField.get(client);
                return screen instanceof Screen s ? s : null;
            }
        } catch (Throwable t) {
            MeowyClient.logOnce("ScreenAccess.current", t);
        }
        return null;
    }

    /** Opens {@code screen} (or closes the current one when {@code null}). */
    public static void set(Minecraft client, Screen screen) {
        if (client == null) {
            return;
        }
        try {
            init();
            Object gui = guiField != null ? guiField.get(client) : null;
            if (gui != null) {
                resolveGuiMethods(gui);
                if (guiScreenSetter != null) {
                    guiScreenSetter.invoke(gui, screen);
                    return;
                }
            }
            if (legacyScreenSetter != null) {
                legacyScreenSetter.invoke(client, screen);
                return;
            }
            MeowyClient.logOnce("ScreenAccess.set.unresolved",
                new IllegalStateException("Could not find a setScreen method on Minecraft or Minecraft.gui"));
        } catch (Throwable t) {
            MeowyClient.logOnce("ScreenAccess.set", t);
        }
    }

    private static synchronized void init() {
        if (initialised) {
            return;
        }
        initialised = true;

        guiField = findField(Minecraft.class, "gui");
        legacyScreenField = findField(Minecraft.class, "screen");
        legacyScreenSetter = findMethod(Minecraft.class, "setScreen", Screen.class);
    }

    private static synchronized void resolveGuiMethods(Object gui) {
        Class<?> cls = gui.getClass();
        if (cls == guiClass) {
            return;
        }
        guiClass = cls;
        guiScreenGetter = findMethod(cls, "screen");
        guiScreenSetter = findMethod(cls, "setScreen", Screen.class);
    }

    private static Field findField(Class<?> owner, String name) {
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> owner, String name, Class<?>... params) {
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                // keep walking up
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }
}
