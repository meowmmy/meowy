package dev.ow.client.config;

/**
 * Fixed tuning values for the Tracer pistol, Cassidy's Peacemaker, and 3rd-person laser visibility.
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

    // ---- Third-Person Laser Visibility & Scaling ---------------------------------------------------------------
    /** Reference camera distance (blocks) at which third-person thickness scaling begins. */
    public static final float THIRD_PERSON_REF_DIST = 3.0f;
    /** Maximum radius multiplier in third person so distant bolts remain clearly visible. */
    public static final float THIRD_PERSON_MAX_RADIUS_SCALE = 3.5f;
    /** Maximum length stretch multiplier in third person. */
    public static final float THIRD_PERSON_MAX_LENGTH_SCALE = 2.4f;
    /** Base half-size of the camera-facing nose glow quad (scaled by radiusScale). */
    public static final float NOSE_GLOW_BASE_SIZE = 0.095f;
    /** Minimum distance ahead of the player's eye in third person before drawing the bolt so it isn't hidden inside the player model. */
    public static final float THIRD_PERSON_PLAYER_CLEARANCE = 1.15f;
    /** Dust trail particle scale in first person. */
    public static final float FIRST_PERSON_TRAIL_SCALE = 0.55f;
    /** Dust trail particle scale in third person. */
    public static final float THIRD_PERSON_TRAIL_SCALE = 0.90f;
}
