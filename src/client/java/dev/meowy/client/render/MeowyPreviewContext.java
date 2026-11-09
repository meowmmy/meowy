package dev.meowy.client.render;

import dev.meowy.client.config.MeowyConfig;

/**
 * Tracks when the Meowy GUI is extracting the 3D Live Preview player doll, stores
 * the user's click-and-drag yaw and limited pitch rotation angles, and drives a
 * 100% client-side, high-refresh-rate (240+ FPS) preview swing animation timer
 * so adjusting Visual Swing Speed never calls LocalPlayer#swing() or sends server packets.
 */
public final class MeowyPreviewContext {
    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static float previewYaw = 0.0f;
    private static float previewPitch = 0.0f;

    // Purely visual, per-frame nanosecond swing preview state (zero network packets / zero clicks)
    private static boolean previewSwingActive = false;
    private static float previewSwingPhase = 0.0f;
    private static long lastFrameNano = 0L;

    private MeowyPreviewContext() {}

    public static void begin(float yaw, float pitch) {
        previewYaw = yaw;
        previewPitch = pitch;
        ACTIVE.set(Boolean.TRUE);
    }

    public static void end() {
        ACTIVE.set(Boolean.FALSE);
    }

    public static boolean isRenderingPreview() {
        return Boolean.TRUE.equals(ACTIVE.get());
    }

    public static float getPreviewYaw() {
        return previewYaw;
    }

    public static float getPreviewPitch() {
        return previewPitch;
    }

    /**
     * Triggers or sustains a 100% client-side visual swing animation for the Live Preview
     * and first-person hand while dragging the Visual Swing Speed slider in MeowyScreen.
     */
    public static void triggerPreviewSwing() {
        if (!previewSwingActive) {
            previewSwingActive = true;
            previewSwingPhase = 0.01f;
            lastFrameNano = System.nanoTime();
        }
    }

    /**
     * Advances the preview swing animation every render frame using high-precision
     * wall-clock delta time so the 3D Live Preview and first-person arm animate
     * at the monitor's full refresh rate (144/240+ FPS) instead of 20 TPS ticks.
     */
    public static void tickFrame(boolean keepLooping) {
        if (!previewSwingActive) {
            return;
        }
        long now = System.nanoTime();
        float dtSeconds = (now - lastFrameNano) / 1_000_000_000.0f;
        lastFrameNano = now;
        if (dtSeconds < 0.0f || dtSeconds > 0.25f) {
            dtSeconds = 0.016f;
        }

        // Vanilla 6-tick swing duration is 0.30 seconds at 1.0x speed
        float speed = Math.clamp(MeowyConfig.get().visualSwingSpeed, 0.20f, 3.00f);
        float cycleDurationSeconds = 0.30f / speed;
        previewSwingPhase += dtSeconds / cycleDurationSeconds;

        if (previewSwingPhase >= 1.0f) {
            if (keepLooping) {
                previewSwingPhase %= 1.0f;
            } else {
                previewSwingPhase = 0.0f;
                previewSwingActive = false;
            }
        }
    }

    public static void stopPreviewSwing() {
        previewSwingActive = false;
        previewSwingPhase = 0.0f;
    }

    public static boolean isPreviewSwingActive() {
        return previewSwingActive;
    }

    public static float getPreviewSwingProgress() {
        return previewSwingActive ? Math.clamp(previewSwingPhase, 0.0f, 1.0f) : 0.0f;
    }
}
