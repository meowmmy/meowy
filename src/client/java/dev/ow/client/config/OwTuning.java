package dev.ow.client.config;

/**
 * Fixed tuning values for the Tracer pistol and Cassidy's Peacemaker.
 * These used to be user-facing settings; they are now constants and no longer appear in the GUI or config/ow.json.
 */
public final class OwTuning {

    private OwTuning() {}

    // ---- Tracer's Pulse Pistol & projectiles -------------------------------------------------------------------
    /** Projectile colour {@code #1FD8FF} (laser bolt, glow and trail particles). */
    public static final int PROJECTILE_RGB = 0x1FD8FF;
    /** Plays the pistol flip / flourish when firing stops (visual only). */
    public static final boolean TRACER_POST_FIRE_ANIMATION = true;
    /** Recovery animation speed multiplier. */
    public static final float TRACER_ANIMATION_SPEED = 1.20f;
    /** How long firing must stop before the recovery animation starts, in milliseconds. */
    public static final int TRACER_FIRING_STOP_DELAY_MS = 110;
    /** Tracer's second pistol is always drawn in the empty hand (visual only). */
    public static final boolean TRACER_DUAL_WIELD = true;

    // ---- Cassidy's Peacemaker ----------------------------------------------------------------------------------
    /** Plays the automatic reload / handling flourish once firing has stopped (visual only). */
    public static final boolean CASSIDY_RELOAD_ANIMATION = true;
    /** Plays the per-shot recoil kick (visual only). */
    public static final boolean CASSIDY_FIRING_ANIMATION = true;
    /** Speed multiplier for all of Cassidy's animations. */
    public static final float CASSIDY_ANIMATION_SPEED = 1.25f;
    /** Recoil intensity multiplier (0.85 = 85%). */
    public static final float CASSIDY_RECOIL_STRENGTH = 0.85f;
    /** How long firing must stop before Cassidy's reload / handling animation starts, in milliseconds. */
    public static final int CASSIDY_FIRING_STOP_DELAY_MS = 350;
}
