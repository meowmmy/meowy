package dev.meowy.client.render;

import dev.meowy.client.MeowyClient;
import dev.meowy.client.config.MeowyConfig;
import dev.meowy.client.mixin.AbstractArrowAccessor;
import dev.ow.client.projectile.LocalArrowTracker;
import it.unimi.dsi.fastutil.ints.Int2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Iterator;

/**
 * 100% client-side Overwatch hitmarker system using the exact pixel data from CrosshairWithHitmarker.png.
 *
 * <p>Includes reliable 1-shot kill detection:
 * <ul>
 *   <li>Accepts freshly killed/dying entities (health <= 0 or deathTime > 0) that CrosshairProjectionHelper skips.</li>
 *   <li>Maintains a 350 ms rolling target snapshot so mobs that are instantly despawned by the server on a 1-shot kill
 *       still match when the arrow vanishes or passes through their hitbox.</li>
 *   <li>Registers local arrows immediately upon classification (even during render frames between client ticks)
 *       so point-blank 1-tick arrows never miss detection.</li>
 * </ul>
 */
public final class HitmarkerManager {

    private static final long FADE_IN_NS  = 22_000_000L;   // 0..22 ms rapid fade-in + outward pop
    private static final long HOLD_END_NS = 62_000_000L;   // 22..62 ms crisp hold at peak opacity
    private static final long TOTAL_NS    = 235_000_000L;  // 62..235 ms smooth Overwatch fade-out
    /** Reduced by ~8% more (1.15f -> 1.06f, max alpha 204) for slightly more transparent Overwatch hitmarkers. */
    private static final float PEAK_ALPHA_MULTIPLIER = 1.06f;

    private static final long RECENT_ARROW_WINDOW_MS  = 320L;
    private static final long RECENT_TARGET_WINDOW_MS = 350L;
    private static final long ENTITY_HIT_DEDUPE_MS    = 85L;
    private static final double VANISH_HITBOX_INFLATE = 1.55;
    private static final double HURT_PROXIMITY_INFLATE = 2.85;

    private static final int[][] HITMARKER_SPANS = {
        {-23, -21, -22, -20, 38, 255, 235, 235},
        {-22, -21, -21, -20, 48, 255, 250, 250},
        {22, -21, 23, -20, 48, 255, 250, 250},
        {23, -21, 24, -20, 38, 255, 235, 235},
        {-22, -20, -21, -19, 71, 255, 255, 255},
        {-21, -20, -20, -19, 63, 255, 255, 255},
        {21, -20, 22, -19, 63, 255, 255, 255},
        {22, -20, 23, -19, 71, 255, 255, 255},
        {-22, -19, -21, -18, 15, 255, 238, 238},
        {-21, -19, -20, -18, 118, 255, 255, 255},
        {-20, -19, -19, -18, 130, 255, 255, 255},
        {-19, -19, -18, -18, 39, 255, 255, 255},
        {19, -19, 20, -18, 39, 255, 255, 255},
        {20, -19, 21, -18, 130, 255, 255, 255},
        {21, -19, 22, -18, 118, 255, 255, 255},
        {22, -19, 23, -18, 15, 255, 238, 238},
        {-21, -18, -20, -17, 52, 255, 235, 235},
        {-20, -18, -19, -17, 145, 255, 255, 255},
        {-19, -18, -18, -17, 171, 255, 255, 255},
        {-18, -18, -17, -17, 94, 255, 255, 255},
        {-17, -18, -16, -17, 49, 255, 250, 250},
        {17, -18, 18, -17, 49, 255, 250, 250},
        {18, -18, 19, -17, 94, 255, 255, 255},
        {19, -18, 20, -17, 171, 255, 255, 255},
        {20, -18, 21, -17, 145, 255, 255, 255},
        {21, -18, 22, -17, 52, 255, 235, 235},
        {-20, -17, -19, -16, 40, 255, 249, 249},
        {-19, -17, -18, -16, 165, 255, 255, 255},
        {-18, -17, -17, -16, 177, 255, 255, 255},
        {-17, -17, -16, -16, 161, 255, 255, 255},
        {-16, -17, -15, -16, 64, 255, 255, 255},
        {16, -17, 17, -16, 64, 255, 255, 255},
        {17, -17, 18, -16, 161, 255, 255, 255},
        {18, -17, 19, -16, 177, 255, 255, 255},
        {19, -17, 20, -16, 165, 255, 255, 255},
        {20, -17, 21, -16, 40, 255, 249, 249},
        {-19, -16, -18, -15, 51, 255, 255, 255},
        {-18, -16, -17, -15, 174, 255, 255, 255},
        {-17, -16, -15, -15, 178, 255, 255, 255},
        {-15, -16, -14, -15, 132, 255, 255, 255},
        {-14, -16, -13, -15, 33, 255, 255, 255},
        {14, -16, 15, -15, 33, 255, 255, 255},
        {15, -16, 16, -15, 132, 255, 255, 255},
        {16, -16, 18, -15, 178, 255, 255, 255},
        {18, -16, 19, -15, 174, 255, 255, 255},
        {19, -16, 20, -15, 51, 255, 255, 255},
        {-18, -15, -17, -14, 73, 255, 255, 255},
        {-17, -15, -14, -14, 178, 255, 255, 255},
        {-14, -15, -13, -14, 173, 255, 255, 255},
        {-13, -15, -12, -14, 90, 255, 255, 255},
        {-12, -15, -11, -14, 36, 255, 234, 234},
        {12, -15, 13, -14, 36, 255, 234, 234},
        {13, -15, 14, -14, 90, 255, 255, 255},
        {14, -15, 15, -14, 173, 255, 255, 255},
        {15, -15, 18, -14, 178, 255, 255, 255},
        {18, -15, 19, -14, 73, 255, 255, 255},
        {-17, -14, -16, -13, 106, 255, 255, 255},
        {-16, -14, -12, -13, 178, 255, 255, 255},
        {-12, -14, -11, -13, 153, 255, 255, 255},
        {-11, -14, -10, -13, 54, 255, 255, 255},
        {11, -14, 12, -13, 54, 255, 255, 255},
        {12, -14, 13, -13, 153, 255, 255, 255},
        {13, -14, 17, -13, 178, 255, 255, 255},
        {17, -14, 18, -13, 106, 255, 255, 255},
        {-17, -13, -16, -12, 28, 255, 255, 255},
        {-16, -13, -15, -12, 136, 255, 255, 255},
        {-15, -13, -10, -12, 178, 255, 255, 255},
        {-10, -13, -9, -12, 125, 255, 255, 255},
        {-9, -13, -8, -12, 32, 255, 247, 247},
        {9, -13, 10, -12, 32, 255, 247, 247},
        {10, -13, 11, -12, 125, 255, 255, 255},
        {11, -13, 16, -12, 178, 255, 255, 255},
        {16, -13, 17, -12, 136, 255, 255, 255},
        {17, -13, 18, -12, 28, 255, 255, 255},
        {-16, -12, -15, -11, 39, 255, 248, 248},
        {-15, -12, -14, -11, 158, 255, 255, 255},
        {-14, -12, -9, -11, 178, 255, 255, 255},
        {-9, -12, -8, -11, 170, 255, 255, 255},
        {-8, -12, -7, -11, 82, 255, 255, 255},
        {-7, -12, -6, -11, 29, 255, 237, 237},
        {7, -12, 8, -11, 29, 255, 237, 237},
        {8, -12, 9, -11, 82, 255, 255, 255},
        {9, -12, 10, -11, 170, 255, 255, 255},
        {10, -12, 15, -11, 178, 255, 255, 255},
        {15, -12, 16, -11, 158, 255, 255, 255},
        {16, -12, 17, -11, 39, 255, 248, 248},
        {-15, -11, -14, -10, 36, 255, 255, 255},
        {-14, -11, -13, -10, 172, 255, 255, 255},
        {-13, -11, -9, -10, 178, 255, 255, 255},
        {-9, -11, -8, -10, 177, 255, 255, 255},
        {-8, -11, -7, -10, 175, 255, 255, 255},
        {-7, -11, -6, -10, 144, 255, 255, 255},
        {-6, -11, -5, -10, 40, 255, 255, 255},
        {6, -11, 7, -10, 40, 255, 255, 255},
        {7, -11, 8, -10, 144, 255, 255, 255},
        {8, -11, 9, -10, 175, 255, 255, 255},
        {9, -11, 10, -10, 177, 255, 255, 255},
        {10, -11, 14, -10, 178, 255, 255, 255},
        {14, -11, 15, -10, 172, 255, 255, 255},
        {15, -11, 16, -10, 36, 255, 255, 255},
        {-14, -10, -13, -9, 61, 255, 255, 255},
        {-13, -10, -10, -9, 178, 255, 255, 255},
        {-10, -10, -9, -9, 177, 255, 255, 255},
        {-9, -10, -8, -9, 176, 255, 255, 255},
        {-8, -10, -7, -9, 175, 255, 255, 255},
        {-7, -10, -6, -9, 172, 255, 255, 255},
        {-6, -10, -5, -9, 167, 255, 255, 255},
        {-5, -10, -4, -9, 101, 255, 255, 255},
        {-4, -10, -3, -9, 30, 255, 246, 246},
        {4, -10, 5, -9, 30, 255, 246, 246},
        {5, -10, 6, -9, 101, 255, 255, 255},
        {6, -10, 7, -9, 167, 255, 255, 255},
        {7, -10, 8, -9, 172, 255, 255, 255},
        {8, -10, 9, -9, 175, 255, 255, 255},
        {9, -10, 10, -9, 176, 255, 255, 255},
        {10, -10, 11, -9, 177, 255, 255, 255},
        {11, -10, 14, -9, 178, 255, 255, 255},
        {14, -10, 15, -9, 61, 255, 255, 255},
        {-13, -9, -12, -8, 98, 255, 255, 255},
        {-12, -9, -11, -8, 178, 255, 255, 255},
        {-11, -9, -9, -8, 177, 255, 255, 255},
        {-9, -9, -8, -8, 175, 255, 255, 255},
        {-8, -9, -7, -8, 174, 255, 255, 255},
        {-7, -9, -6, -8, 170, 255, 255, 255},
        {-6, -9, -5, -8, 166, 255, 255, 255},
        {-5, -9, -4, -8, 160, 255, 255, 255},
        {-4, -9, -3, -8, 140, 255, 255, 255},
        {-3, -9, -2, -8, 56, 255, 255, 255},
        {3, -9, 4, -8, 56, 255, 255, 255},
        {4, -9, 5, -8, 140, 255, 255, 255},
        {5, -9, 6, -8, 160, 255, 255, 255},
        {6, -9, 7, -8, 166, 255, 255, 255},
        {7, -9, 8, -8, 170, 255, 255, 255},
        {8, -9, 9, -8, 174, 255, 255, 255},
        {9, -9, 10, -8, 175, 255, 255, 255},
        {10, -9, 12, -8, 177, 255, 255, 255},
        {12, -9, 13, -8, 178, 255, 255, 255},
        {13, -9, 14, -8, 98, 255, 255, 255},
        {-13, -8, -12, -7, 15, 255, 238, 238},
        {-12, -8, -11, -7, 127, 255, 255, 255},
        {-11, -8, -10, -7, 176, 255, 255, 255},
        {-10, -8, -9, -7, 175, 255, 255, 255},
        {-9, -8, -8, -7, 174, 255, 255, 255},
        {-8, -8, -7, -7, 171, 255, 255, 255},
        {-7, -8, -6, -7, 168, 255, 255, 255},
        {-6, -8, -5, -7, 163, 255, 255, 255},
        {-5, -8, -4, -7, 156, 255, 255, 255},
        {-4, -8, -3, -7, 149, 255, 255, 255},
        {-3, -8, -2, -7, 123, 255, 255, 255},
        {-2, -8, -1, -7, 21, 255, 243, 243},
        {2, -8, 3, -7, 21, 255, 243, 243},
        {3, -8, 4, -7, 123, 255, 255, 255},
        {4, -8, 5, -7, 149, 255, 255, 255},
        {5, -8, 6, -7, 156, 255, 255, 255},
        {6, -8, 7, -7, 163, 255, 255, 255},
        {7, -8, 8, -7, 168, 255, 255, 255},
        {8, -8, 9, -7, 171, 255, 255, 255},
        {9, -8, 10, -7, 174, 255, 255, 255},
        {10, -8, 11, -7, 175, 255, 255, 255},
        {11, -8, 12, -7, 176, 255, 255, 255},
        {12, -8, 13, -7, 127, 255, 255, 255},
        {13, -8, 14, -7, 15, 255, 238, 238},
        {-12, -7, -11, -6, 39, 255, 242, 242},
        {-11, -7, -10, -6, 148, 255, 255, 255},
        {-10, -7, -9, -6, 174, 255, 255, 255},
        {-9, -7, -8, -6, 171, 255, 255, 255},
        {-8, -7, -7, -6, 169, 255, 255, 255},
        {-7, -7, -6, -6, 165, 255, 255, 255},
        {-6, -7, -5, -6, 158, 255, 255, 255},
        {-5, -7, -4, -6, 151, 255, 255, 255},
        {-4, -7, -3, -6, 133, 255, 255, 255},
        {-3, -7, -2, -6, 25, 255, 245, 245},
        {3, -7, 4, -6, 25, 255, 245, 245},
        {4, -7, 5, -6, 133, 255, 255, 255},
        {5, -7, 6, -6, 151, 255, 255, 255},
        {6, -7, 7, -6, 158, 255, 255, 255},
        {7, -7, 8, -6, 165, 255, 255, 255},
        {8, -7, 9, -6, 169, 255, 255, 255},
        {9, -7, 10, -6, 171, 255, 255, 255},
        {10, -7, 11, -6, 174, 255, 255, 255},
        {11, -7, 12, -6, 148, 255, 255, 255},
        {12, -7, 13, -6, 39, 255, 242, 242},
        {-11, -6, -10, -5, 31, 255, 247, 247},
        {-10, -6, -9, -5, 161, 255, 255, 255},
        {-9, -6, -8, -5, 168, 255, 255, 255},
        {-8, -6, -7, -5, 165, 255, 255, 255},
        {-7, -6, -6, -5, 160, 255, 255, 255},
        {-6, -6, -5, -5, 153, 255, 255, 255},
        {-5, -6, -4, -5, 135, 255, 255, 255},
        {-4, -6, -3, -5, 28, 255, 255, 255},
        {4, -6, 5, -5, 28, 255, 255, 255},
        {5, -6, 6, -5, 135, 255, 255, 255},
        {6, -6, 7, -5, 153, 255, 255, 255},
        {7, -6, 8, -5, 160, 255, 255, 255},
        {8, -6, 9, -5, 165, 255, 255, 255},
        {9, -6, 10, -5, 168, 255, 255, 255},
        {10, -6, 11, -5, 161, 255, 255, 255},
        {11, -6, 12, -5, 31, 255, 247, 247},
        {-10, -5, -9, -4, 48, 255, 255, 255},
        {-9, -5, -8, -4, 162, 255, 255, 255},
        {-8, -5, -7, -4, 158, 255, 255, 255},
        {-7, -5, -6, -4, 153, 255, 255, 255},
        {-6, -5, -5, -4, 139, 255, 255, 255},
        {-5, -5, -4, -4, 28, 255, 255, 255},
        {5, -5, 6, -4, 28, 255, 255, 255},
        {6, -5, 7, -4, 139, 255, 255, 255},
        {7, -5, 8, -4, 153, 255, 255, 255},
        {8, -5, 9, -4, 158, 255, 255, 255},
        {9, -5, 10, -4, 162, 255, 255, 255},
        {10, -5, 11, -4, 48, 255, 255, 255},
        {-9, -4, -8, -3, 73, 255, 255, 255},
        {-8, -4, -7, -3, 151, 255, 255, 255},
        {-7, -4, -6, -3, 139, 255, 255, 255},
        {-6, -4, -5, -3, 34, 255, 255, 255},
        {6, -4, 7, -3, 34, 255, 255, 255},
        {7, -4, 8, -3, 139, 255, 255, 255},
        {8, -4, 9, -3, 151, 255, 255, 255},
        {9, -4, 10, -3, 73, 255, 255, 255},
        {-8, -3, -7, -2, 90, 255, 255, 255},
        {-7, -3, -6, -2, 34, 255, 255, 255},
        {7, -3, 8, -2, 34, 255, 255, 255},
        {8, -3, 9, -2, 90, 255, 255, 255},
        {-9, 2, -8, 3, 90, 255, 255, 255},
        {-8, 2, -7, 3, 34, 255, 255, 255},
        {7, 2, 8, 3, 34, 255, 255, 255},
        {8, 2, 9, 3, 90, 255, 255, 255},
        {-10, 3, -9, 4, 73, 255, 255, 255},
        {-9, 3, -8, 4, 151, 255, 255, 255},
        {-8, 3, -7, 4, 139, 255, 255, 255},
        {-7, 3, -6, 4, 34, 255, 255, 255},
        {6, 3, 7, 4, 34, 255, 255, 255},
        {7, 3, 8, 4, 139, 255, 255, 255},
        {8, 3, 9, 4, 151, 255, 255, 255},
        {9, 3, 10, 4, 73, 255, 255, 255},
        {-11, 4, -10, 5, 48, 255, 255, 255},
        {-10, 4, -9, 5, 162, 255, 255, 255},
        {-9, 4, -8, 5, 158, 255, 255, 255},
        {-8, 4, -7, 5, 153, 255, 255, 255},
        {-7, 4, -6, 5, 139, 255, 255, 255},
        {-6, 4, -5, 5, 28, 255, 255, 255},
        {5, 4, 6, 5, 28, 255, 255, 255},
        {6, 4, 7, 5, 139, 255, 255, 255},
        {7, 4, 8, 5, 153, 255, 255, 255},
        {8, 4, 9, 5, 158, 255, 255, 255},
        {9, 4, 10, 5, 162, 255, 255, 255},
        {10, 4, 11, 5, 48, 255, 255, 255},
        {-12, 5, -11, 6, 31, 255, 247, 247},
        {-11, 5, -10, 6, 161, 255, 255, 255},
        {-10, 5, -9, 6, 168, 255, 255, 255},
        {-9, 5, -8, 6, 165, 255, 255, 255},
        {-8, 5, -7, 6, 160, 255, 255, 255},
        {-7, 5, -6, 6, 153, 255, 255, 255},
        {-6, 5, -5, 6, 135, 255, 255, 255},
        {-5, 5, -4, 6, 28, 255, 255, 255},
        {4, 5, 5, 6, 28, 255, 255, 255},
        {5, 5, 6, 6, 135, 255, 255, 255},
        {6, 5, 7, 6, 153, 255, 255, 255},
        {7, 5, 8, 6, 160, 255, 255, 255},
        {8, 5, 9, 6, 165, 255, 255, 255},
        {9, 5, 10, 6, 168, 255, 255, 255},
        {10, 5, 11, 6, 161, 255, 255, 255},
        {11, 5, 12, 6, 31, 255, 247, 247},
        {-13, 6, -12, 7, 39, 255, 242, 242},
        {-12, 6, -11, 7, 148, 255, 255, 255},
        {-11, 6, -10, 7, 174, 255, 255, 255},
        {-10, 6, -9, 7, 171, 255, 255, 255},
        {-9, 6, -8, 7, 169, 255, 255, 255},
        {-8, 6, -7, 7, 165, 255, 255, 255},
        {-7, 6, -6, 7, 158, 255, 255, 255},
        {-6, 6, -5, 7, 151, 255, 255, 255},
        {-5, 6, -4, 7, 133, 255, 255, 255},
        {-4, 6, -3, 7, 25, 255, 245, 245},
        {3, 6, 4, 7, 25, 255, 245, 245},
        {4, 6, 5, 7, 133, 255, 255, 255},
        {5, 6, 6, 7, 151, 255, 255, 255},
        {6, 6, 7, 7, 158, 255, 255, 255},
        {7, 6, 8, 7, 165, 255, 255, 255},
        {8, 6, 9, 7, 169, 255, 255, 255},
        {9, 6, 10, 7, 171, 255, 255, 255},
        {10, 6, 11, 7, 174, 255, 255, 255},
        {11, 6, 12, 7, 148, 255, 255, 255},
        {12, 6, 13, 7, 39, 255, 242, 242},
        {-14, 7, -13, 8, 15, 255, 238, 238},
        {-13, 7, -12, 8, 127, 255, 255, 255},
        {-12, 7, -11, 8, 176, 255, 255, 255},
        {-11, 7, -10, 8, 175, 255, 255, 255},
        {-10, 7, -9, 8, 174, 255, 255, 255},
        {-9, 7, -8, 8, 171, 255, 255, 255},
        {-8, 7, -7, 8, 168, 255, 255, 255},
        {-7, 7, -6, 8, 163, 255, 255, 255},
        {-6, 7, -5, 8, 156, 255, 255, 255},
        {-5, 7, -4, 8, 149, 255, 255, 255},
        {-4, 7, -3, 8, 123, 255, 255, 255},
        {-3, 7, -2, 8, 21, 255, 243, 243},
        {2, 7, 3, 8, 21, 255, 243, 243},
        {3, 7, 4, 8, 123, 255, 255, 255},
        {4, 7, 5, 8, 149, 255, 255, 255},
        {5, 7, 6, 8, 156, 255, 255, 255},
        {6, 7, 7, 8, 163, 255, 255, 255},
        {7, 7, 8, 8, 168, 255, 255, 255},
        {8, 7, 9, 8, 171, 255, 255, 255},
        {9, 7, 10, 8, 174, 255, 255, 255},
        {10, 7, 11, 8, 175, 255, 255, 255},
        {11, 7, 12, 8, 176, 255, 255, 255},
        {12, 7, 13, 8, 127, 255, 255, 255},
        {13, 7, 14, 8, 15, 255, 238, 238},
        {-14, 8, -13, 9, 98, 255, 255, 255},
        {-13, 8, -12, 9, 178, 255, 255, 255},
        {-12, 8, -10, 9, 177, 255, 255, 255},
        {-10, 8, -9, 9, 175, 255, 255, 255},
        {-9, 8, -8, 9, 174, 255, 255, 255},
        {-8, 8, -7, 9, 170, 255, 255, 255},
        {-7, 8, -6, 9, 166, 255, 255, 255},
        {-6, 8, -5, 9, 160, 255, 255, 255},
        {-5, 8, -4, 9, 140, 255, 255, 255},
        {-4, 8, -3, 9, 56, 255, 255, 255},
        {3, 8, 4, 9, 56, 255, 255, 255},
        {4, 8, 5, 9, 140, 255, 255, 255},
        {5, 8, 6, 9, 160, 255, 255, 255},
        {6, 8, 7, 9, 166, 255, 255, 255},
        {7, 8, 8, 9, 170, 255, 255, 255},
        {8, 8, 9, 9, 174, 255, 255, 255},
        {9, 8, 10, 9, 175, 255, 255, 255},
        {10, 8, 12, 9, 177, 255, 255, 255},
        {12, 8, 13, 9, 178, 255, 255, 255},
        {13, 8, 14, 9, 98, 255, 255, 255},
        {-15, 9, -14, 10, 61, 255, 255, 255},
        {-14, 9, -11, 10, 178, 255, 255, 255},
        {-11, 9, -10, 10, 177, 255, 255, 255},
        {-10, 9, -9, 10, 176, 255, 255, 255},
        {-9, 9, -8, 10, 175, 255, 255, 255},
        {-8, 9, -7, 10, 172, 255, 255, 255},
        {-7, 9, -6, 10, 167, 255, 255, 255},
        {-6, 9, -5, 10, 101, 255, 255, 255},
        {-5, 9, -4, 10, 30, 255, 246, 246},
        {4, 9, 5, 10, 30, 255, 246, 246},
        {5, 9, 6, 10, 101, 255, 255, 255},
        {6, 9, 7, 10, 167, 255, 255, 255},
        {7, 9, 8, 10, 172, 255, 255, 255},
        {8, 9, 9, 10, 175, 255, 255, 255},
        {9, 9, 10, 10, 176, 255, 255, 255},
        {10, 9, 11, 10, 177, 255, 255, 255},
        {11, 9, 14, 10, 178, 255, 255, 255},
        {14, 9, 15, 10, 61, 255, 255, 255},
        {-16, 10, -15, 11, 36, 255, 255, 255},
        {-15, 10, -14, 11, 172, 255, 255, 255},
        {-14, 10, -10, 11, 178, 255, 255, 255},
        {-10, 10, -9, 11, 177, 255, 255, 255},
        {-9, 10, -8, 11, 175, 255, 255, 255},
        {-8, 10, -7, 11, 144, 255, 255, 255},
        {-7, 10, -6, 11, 40, 255, 255, 255},
        {6, 10, 7, 11, 40, 255, 255, 255},
        {7, 10, 8, 11, 144, 255, 255, 255},
        {8, 10, 9, 11, 175, 255, 255, 255},
        {9, 10, 10, 11, 177, 255, 255, 255},
        {10, 10, 14, 11, 178, 255, 255, 255},
        {14, 10, 15, 11, 172, 255, 255, 255},
        {15, 10, 16, 11, 36, 255, 255, 255},
        {-17, 11, -16, 12, 39, 255, 248, 248},
        {-16, 11, -15, 12, 158, 255, 255, 255},
        {-15, 11, -10, 12, 178, 255, 255, 255},
        {-10, 11, -9, 12, 170, 255, 255, 255},
        {-9, 11, -8, 12, 82, 255, 255, 255},
        {-8, 11, -7, 12, 29, 255, 237, 237},
        {7, 11, 8, 12, 29, 255, 237, 237},
        {8, 11, 9, 12, 82, 255, 255, 255},
        {9, 11, 10, 12, 170, 255, 255, 255},
        {10, 11, 15, 12, 178, 255, 255, 255},
        {15, 11, 16, 12, 158, 255, 255, 255},
        {16, 11, 17, 12, 39, 255, 248, 248},
        {-18, 12, -17, 13, 28, 255, 255, 255},
        {-17, 12, -16, 13, 136, 255, 255, 255},
        {-16, 12, -11, 13, 178, 255, 255, 255},
        {-11, 12, -10, 13, 125, 255, 255, 255},
        {-10, 12, -9, 13, 32, 255, 247, 247},
        {9, 12, 10, 13, 32, 255, 247, 247},
        {10, 12, 11, 13, 125, 255, 255, 255},
        {11, 12, 16, 13, 178, 255, 255, 255},
        {16, 12, 17, 13, 136, 255, 255, 255},
        {17, 12, 18, 13, 28, 255, 255, 255},
        {-18, 13, -17, 14, 106, 255, 255, 255},
        {-17, 13, -13, 14, 178, 255, 255, 255},
        {-13, 13, -12, 14, 153, 255, 255, 255},
        {-12, 13, -11, 14, 54, 255, 255, 255},
        {11, 13, 12, 14, 54, 255, 255, 255},
        {12, 13, 13, 14, 153, 255, 255, 255},
        {13, 13, 17, 14, 178, 255, 255, 255},
        {17, 13, 18, 14, 106, 255, 255, 255},
        {-19, 14, -18, 15, 73, 255, 255, 255},
        {-18, 14, -15, 15, 178, 255, 255, 255},
        {-15, 14, -14, 15, 173, 255, 255, 255},
        {-14, 14, -13, 15, 90, 255, 255, 255},
        {-13, 14, -12, 15, 36, 255, 234, 234},
        {12, 14, 13, 15, 36, 255, 234, 234},
        {13, 14, 14, 15, 90, 255, 255, 255},
        {14, 14, 15, 15, 173, 255, 255, 255},
        {15, 14, 18, 15, 178, 255, 255, 255},
        {18, 14, 19, 15, 73, 255, 255, 255},
        {-20, 15, -19, 16, 51, 255, 255, 255},
        {-19, 15, -18, 16, 174, 255, 255, 255},
        {-18, 15, -16, 16, 178, 255, 255, 255},
        {-16, 15, -15, 16, 132, 255, 255, 255},
        {-15, 15, -14, 16, 33, 255, 255, 255},
        {14, 15, 15, 16, 33, 255, 255, 255},
        {15, 15, 16, 16, 132, 255, 255, 255},
        {16, 15, 18, 16, 178, 255, 255, 255},
        {18, 15, 19, 16, 174, 255, 255, 255},
        {19, 15, 20, 16, 51, 255, 255, 255},
        {-21, 16, -20, 17, 40, 255, 249, 249},
        {-20, 16, -19, 17, 165, 255, 255, 255},
        {-19, 16, -18, 17, 177, 255, 255, 255},
        {-18, 16, -17, 17, 161, 255, 255, 255},
        {-17, 16, -16, 17, 64, 255, 255, 255},
        {16, 16, 17, 17, 64, 255, 255, 255},
        {17, 16, 18, 17, 161, 255, 255, 255},
        {18, 16, 19, 17, 177, 255, 255, 255},
        {19, 16, 20, 17, 165, 255, 255, 255},
        {20, 16, 21, 17, 40, 255, 249, 249},
        {-22, 17, -21, 18, 52, 255, 235, 235},
        {-21, 17, -20, 18, 145, 255, 255, 255},
        {-20, 17, -19, 18, 171, 255, 255, 255},
        {-19, 17, -18, 18, 94, 255, 255, 255},
        {-18, 17, -17, 18, 49, 255, 250, 250},
        {17, 17, 18, 18, 49, 255, 250, 250},
        {18, 17, 19, 18, 94, 255, 255, 255},
        {19, 17, 20, 18, 171, 255, 255, 255},
        {20, 17, 21, 18, 145, 255, 255, 255},
        {21, 17, 22, 18, 52, 255, 235, 235},
        {-23, 18, -22, 19, 15, 255, 238, 238},
        {-22, 18, -21, 19, 118, 255, 255, 255},
        {-21, 18, -20, 19, 130, 255, 255, 255},
        {-20, 18, -19, 19, 39, 255, 255, 255},
        {19, 18, 20, 19, 39, 255, 255, 255},
        {20, 18, 21, 19, 130, 255, 255, 255},
        {21, 18, 22, 19, 118, 255, 255, 255},
        {22, 18, 23, 19, 15, 255, 238, 238},
        {-23, 19, -22, 20, 71, 255, 255, 255},
        {-22, 19, -21, 20, 63, 255, 255, 255},
        {21, 19, 22, 20, 63, 255, 255, 255},
        {22, 19, 23, 20, 71, 255, 255, 255},
        {-24, 20, -23, 21, 38, 255, 235, 235},
        {-23, 20, -22, 21, 48, 255, 250, 250},
        {22, 20, 23, 21, 48, 255, 250, 250},
        {23, 20, 24, 21, 38, 255, 235, 235}
    };

    private static final class TrackedArrow {
        final int id;
        double startX, startY, startZ;
        double lastX, lastY, lastZ;
        double velX, velY, velZ;
        boolean lastInGround;
        boolean activeInLevel;
        boolean triggeredHit;
        long vanishedAtMs;

        TrackedArrow(int id, Vec3 pos, Vec3 vel, boolean inGround) {
            this.id = id;
            this.startX = pos.x;
            this.startY = pos.y;
            this.startZ = pos.z;
            this.update(pos, vel, inGround);
        }

        void update(Vec3 pos, Vec3 vel, boolean inGround) {
            this.lastX = pos.x;
            this.lastY = pos.y;
            this.lastZ = pos.z;
            if (vel.lengthSqr() > 1.0E-4) {
                this.velX = vel.x;
                this.velY = vel.y;
                this.velZ = vel.z;
            }
            this.lastInGround = inGround;
            this.activeInLevel = true;
        }
    }

    /** Rolling snapshot of valid target hitboxes so 1-shot mobs that die or despawn on the same tick still register. */
    private static final class TargetSnapshot {
        final int id;
        String name;
        AABB box;
        long lastSeenAliveMs;

        TargetSnapshot(int id, String name, AABB box, long nowMs) {
            this.id = id;
            this.name = name;
            this.box = box;
            this.lastSeenAliveMs = nowMs;
        }
    }

    private static final Int2ObjectOpenHashMap<TrackedArrow> TRACKED_ARROWS = new Int2ObjectOpenHashMap<>();
    private static final Int2ObjectOpenHashMap<TargetSnapshot> RECENT_TARGETS = new Int2ObjectOpenHashMap<>();
    private static final Int2IntOpenHashMap LAST_ENTITY_HURT_TIME = new Int2IntOpenHashMap();
    private static final Int2BooleanOpenHashMap LAST_ENTITY_ALIVE = new Int2BooleanOpenHashMap();
    private static final Int2LongOpenHashMap LAST_HIT_BY_ENTITY_MS = new Int2LongOpenHashMap();
    private static final IntOpenHashSet SEEN_ARROW_IDS = new IntOpenHashSet();
    private static final IntOpenHashSet SEEN_LIVING_IDS = new IntOpenHashSet();

    private static volatile long lastHitNano = 0L;
    private static volatile boolean debug = false;

    private HitmarkerManager() {}

    public static boolean toggleDebug() {
        debug = !debug;
        return debug;
    }

    /**
     * Called immediately from LocalArrowTracker when a local arrow is classified (even mid-frame before tick)
     * so fast 1-shot arrows that vanish within a single tick are always tracked.
     */
    public static synchronized void onLocalArrowSeen(AbstractArrow arrow) {
        if (arrow == null || (!MeowyConfig.get().hitmarkersEnabled && !debug)) {
            return;
        }
        int id = arrow.getId();
        boolean inGround = ((AbstractArrowAccessor) arrow).ow$isInGround();
        TrackedArrow existing = TRACKED_ARROWS.get(id);
        if (existing == null) {
            TRACKED_ARROWS.put(id, new TrackedArrow(id, arrow.position(), arrow.getDeltaMovement(), inGround));
        } else {
            existing.update(arrow.position(), arrow.getDeltaMovement(), inGround);
        }
    }

    public static synchronized void triggerHit(String signal, int targetId, String targetName, double dist) {
        long nowMs = System.currentTimeMillis();
        if (targetId != -1) {
            long prevMs = LAST_HIT_BY_ENTITY_MS.getOrDefault(targetId, -1L);
            if (prevMs != -1L && (nowMs - prevMs) < ENTITY_HIT_DEDUPE_MS) {
                if (debug) {
                    MeowyClient.LOGGER.info(
                        "[Meowy][Hitmarker] Deduplicated {} on {} (id={}, dist={})",
                        signal, targetName, targetId, dist
                    );
                }
                return;
            }
            LAST_HIT_BY_ENTITY_MS.put(targetId, nowMs);
        }

        lastHitNano = System.nanoTime();
        if (debug) {
            MeowyClient.LOGGER.info(
                "[Meowy][Hitmarker] Fired signal={} target={} (id={}) dist={}",
                signal, targetName != null ? targetName : "unknown", targetId, dist
            );
        }
    }

    /**
     * Checks whether an entity is a valid attackable target, INCLUDING entities that just died on this tick
     * (health <= 0 or deathTime <= 10) from a 1-shot kill!
     */
    private static boolean isValidOrFreshlyKilledTarget(Player localPlayer, Entity entity) {
        if (entity == null || entity == localPlayer || entity instanceof AbstractArrow) {
            return false;
        }
        if (!entity.isAttackable() || entity.isInvisibleTo(localPlayer)) {
            return false;
        }
        if (entity.isAlive() && entity.isPickable()) {
            return true;
        }
        if (entity instanceof LivingEntity living) {
            return living.deathTime >= 0 && living.deathTime <= 10;
        }
        return false;
    }

    public static synchronized void tick(Minecraft client) {
        if (client == null || client.level == null || client.player == null) {
            clearState();
            return;
        }

        if (!MeowyConfig.get().hitmarkersEnabled && !debug) {
            if (!TRACKED_ARROWS.isEmpty()) {
                clearState();
            }
            return;
        }

        LocalArrowTracker.pollLocalShots(client);

        ClientLevel level = client.level;
        LocalPlayer me = client.player;
        long nowMs = System.currentTimeMillis();

        // 1. Update active local arrows and check for direct in-flight hitbox penetration
        SEEN_ARROW_IDS.clear();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof AbstractArrow arrow && LocalArrowTracker.isLocalPlayerArrow(arrow)) {
                int id = arrow.getId();
                SEEN_ARROW_IDS.add(id);
                boolean inGround = ((AbstractArrowAccessor) arrow).ow$isInGround();
                TrackedArrow tracked = TRACKED_ARROWS.get(id);
                if (tracked == null) {
                    tracked = new TrackedArrow(id, arrow.position(), arrow.getDeltaMovement(), inGround);
                    TRACKED_ARROWS.put(id, tracked);
                } else {
                    tracked.update(arrow.position(), arrow.getDeltaMovement(), inGround);
                }
            }
        }

        // 2. Scan target entities FIRST so newly killed/1-shotted mobs & hurt spikes are captured before arrow vanish cleanup
        SEEN_LIVING_IDS.clear();
        for (Entity entity : level.entitiesForRendering()) {
            if (!isValidOrFreshlyKilledTarget(me, entity)) {
                continue;
            }
            int id = entity.getId();
            SEEN_LIVING_IDS.add(id);
            boolean aliveNow = entity.isAlive() && (!(entity instanceof LivingEntity lv) || lv.getHealth() > 0.0f);
            boolean wasAlive = LAST_ENTITY_ALIVE.getOrDefault(id, true);
            LAST_ENTITY_ALIVE.put(id, aliveNow);

            if (aliveNow) {
                TargetSnapshot snap = RECENT_TARGETS.get(id);
                if (snap == null) {
                    RECENT_TARGETS.put(id, new TargetSnapshot(id, entity.getName().getString(), entity.getBoundingBox(), nowMs));
                } else {
                    snap.box = entity.getBoundingBox();
                    snap.lastSeenAliveMs = nowMs;
                }
            }

            if (entity instanceof LivingEntity living) {
                int hurtTime = living.hurtTime;
                int prevHurt = LAST_ENTITY_HURT_TIME.getOrDefault(id, 0);
                LAST_ENTITY_HURT_TIME.put(id, hurtTime);

                boolean hurtSpike = hurtTime > prevHurt && hurtTime >= Math.max(1, living.hurtDuration - 2);
                boolean oneShotDeath = wasAlive && !aliveNow;

                if (hurtSpike || oneShotDeath) {
                    double bestDist = Double.MAX_VALUE;
                    TrackedArrow matchedArrow = null;
                    for (TrackedArrow tracked : TRACKED_ARROWS.values()) {
                        if (!tracked.activeInLevel && (nowMs - tracked.vanishedAtMs) > RECENT_ARROW_WINDOW_MS) {
                            continue;
                        }
                        if (isArrowNearBox(tracked, living.getBoundingBox(), HURT_PROXIMITY_INFLATE)) {
                            double d = distanceToBox(tracked.lastX, tracked.lastY, tracked.lastZ, living.getBoundingBox());
                            if (d < bestDist) {
                                bestDist = d;
                                matchedArrow = tracked;
                            }
                        }
                    }
                    if (matchedArrow != null) {
                        matchedArrow.triggeredHit = true;
                        triggerHit(
                            oneShotDeath ? "entity_1shot_death_near_arrow" : "entity_hurt_near_arrow",
                            id,
                            living.getName().getString(),
                            bestDist
                        );
                    }
                }
            }
        }

        // 3. Check arrows that vanished this tick while not in ground (against both live entities AND recent 1-shot target snapshots)
        Iterator<Int2ObjectMap.Entry<TrackedArrow>> it = TRACKED_ARROWS.int2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            TrackedArrow tracked = it.next().getValue();
            if (tracked.activeInLevel && !SEEN_ARROW_IDS.contains(tracked.id)) {
                tracked.activeInLevel = false;
                tracked.vanishedAtMs = nowMs;

                Entity stillThere = level.getEntity(tracked.id);
                boolean inGroundNow = tracked.lastInGround
                    || (stillThere instanceof AbstractArrow a && ((AbstractArrowAccessor) a).ow$isInGround());

                if (!inGroundNow && !tracked.triggeredHit) {
                    TargetSnapshot hitSnap = findTargetNearArrowPath(level, me, tracked, VANISH_HITBOX_INFLATE, nowMs);
                    if (hitSnap != null) {
                        tracked.triggeredHit = true;
                        double dist = distanceToBox(tracked.lastX, tracked.lastY, tracked.lastZ, hitSnap.box);
                        triggerHit("arrow_vanish_near_target", hitSnap.id, hitSnap.name, dist);
                    } else if (debug) {
                        MeowyClient.LOGGER.info(
                            "[Meowy][Hitmarker] Local arrow id={} vanished in air at ({}, {}, {}) with no valid target nearby",
                            tracked.id, tracked.lastX, tracked.lastY, tracked.lastZ
                        );
                    }
                }
            }

            if (!tracked.activeInLevel && (nowMs - tracked.vanishedAtMs) > RECENT_ARROW_WINDOW_MS) {
                it.remove();
            }
        }

        // 4. Also check if a tracked target entity vanished (instant server despawn on 1-shot kill) while a local arrow was flying through it
        Iterator<Int2ObjectMap.Entry<TargetSnapshot>> snapIt = RECENT_TARGETS.int2ObjectEntrySet().fastIterator();
        while (snapIt.hasNext()) {
            TargetSnapshot snap = snapIt.next().getValue();
            if (!SEEN_LIVING_IDS.contains(snap.id) && (nowMs - snap.lastSeenAliveMs) <= 100L) {
                for (TrackedArrow tracked : TRACKED_ARROWS.values()) {
                    if (tracked.triggeredHit) continue;
                    if (!tracked.activeInLevel && (nowMs - tracked.vanishedAtMs) > RECENT_ARROW_WINDOW_MS) continue;
                    if (isArrowNearBox(tracked, snap.box, VANISH_HITBOX_INFLATE)) {
                        tracked.triggeredHit = true;
                        double d = distanceToBox(tracked.lastX, tracked.lastY, tracked.lastZ, snap.box);
                        triggerHit("target_despawned_1shot_near_arrow", snap.id, snap.name, d);
                        break;
                    }
                }
            }
            if ((nowMs - snap.lastSeenAliveMs) > RECENT_TARGET_WINDOW_MS) {
                snapIt.remove();
            }
        }

        if (LAST_ENTITY_HURT_TIME.size() > 256) {
            LAST_ENTITY_HURT_TIME.keySet().retainAll(SEEN_LIVING_IDS);
            LAST_ENTITY_ALIVE.keySet().retainAll(SEEN_LIVING_IDS);
        }
        if (LAST_HIT_BY_ENTITY_MS.size() > 128) {
            LAST_HIT_BY_ENTITY_MS.int2LongEntrySet().removeIf(e -> (nowMs - e.getLongValue()) > 2000L);
        }
    }

    private static void clearState() {
        TRACKED_ARROWS.clear();
        RECENT_TARGETS.clear();
        LAST_ENTITY_HURT_TIME.clear();
        LAST_ENTITY_ALIVE.clear();
        LAST_HIT_BY_ENTITY_MS.clear();
    }

    private static TargetSnapshot findTargetNearArrowPath(
        ClientLevel level,
        LocalPlayer me,
        TrackedArrow tracked,
        double inflate,
        long nowMs
    ) {
        TargetSnapshot best = null;
        double bestDist = Double.MAX_VALUE;

        // Check current entities in level (including freshly killed ones)
        for (Entity entity : level.entitiesForRendering()) {
            if (!isValidOrFreshlyKilledTarget(me, entity)) {
                continue;
            }
            AABB box = entity.getBoundingBox();
            if (isArrowNearBox(tracked, box, inflate)) {
                double d = distanceToBox(tracked.lastX, tracked.lastY, tracked.lastZ, box);
                if (d < bestDist) {
                    bestDist = d;
                    best = new TargetSnapshot(entity.getId(), entity.getName().getString(), box, nowMs);
                }
            }
        }

        // Also check RECENT_TARGETS in case the 1-shotted mob was despawned by the server on the same tick
        for (TargetSnapshot snap : RECENT_TARGETS.values()) {
            if ((nowMs - snap.lastSeenAliveMs) > RECENT_TARGET_WINDOW_MS) {
                continue;
            }
            if (isArrowNearBox(tracked, snap.box, inflate)) {
                double d = distanceToBox(tracked.lastX, tracked.lastY, tracked.lastZ, snap.box);
                if (d < bestDist) {
                    bestDist = d;
                    best = snap;
                }
            }
        }

        return best;
    }

    private static boolean isArrowNearBox(TrackedArrow t, AABB rawBox, double inflate) {
        AABB box = rawBox.inflate(inflate);
        // Check current position, start position, and forward/backward ray segment along velocity
        double nx = t.lastX + t.velX * 1.85;
        double ny = t.lastY + t.velY * 1.85;
        double nz = t.lastZ + t.velZ * 1.85;
        if (box.contains(t.lastX, t.lastY, t.lastZ) || box.contains(nx, ny, nz)) {
            return true;
        }
        Vec3 prevPos = new Vec3(t.startX, t.startY, t.startZ);
        Vec3 lastPos = new Vec3(t.lastX - t.velX * 0.75, t.lastY - t.velY * 0.75, t.lastZ - t.velZ * 0.75);
        Vec3 nextPos = new Vec3(nx, ny, nz);
        return box.clip(lastPos, nextPos).isPresent() || box.clip(prevPos, nextPos).isPresent();
    }

    private static double distanceToBox(double x, double y, double z, AABB box) {
        double cx = Mth.clamp(x, box.minX, box.maxX);
        double cy = Mth.clamp(y, box.minY, box.maxY);
        double cz = Mth.clamp(z, box.minZ, box.maxZ);
        double dx = x - cx;
        double dy = y - cy;
        double dz = z - cz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public static void render(GuiGraphicsExtractor graphics, float centerX, float centerY) {
        if (!MeowyConfig.get().hitmarkersEnabled) {
            return;
        }
        long elapsedNs = System.nanoTime() - lastHitNano;
        if (lastHitNano == 0L || elapsedNs < 0L || elapsedNs >= TOTAL_NS) {
            return;
        }

        float opacityMultiplier;
        float popScale;

        if (elapsedNs < FADE_IN_NS) {
            float u = (float) elapsedNs / (float) FADE_IN_NS;
            float easeOut = 1.0f - (1.0f - u) * (1.0f - u);
            opacityMultiplier = (0.45f + 0.55f * easeOut) * PEAK_ALPHA_MULTIPLIER;
            popScale = Mth.lerp(easeOut, 0.88f, 1.05f);
        } else if (elapsedNs < HOLD_END_NS) {
            float u = (float) (elapsedNs - FADE_IN_NS) / (float) (HOLD_END_NS - FADE_IN_NS);
            opacityMultiplier = PEAK_ALPHA_MULTIPLIER;
            popScale = Mth.lerp(u, 1.05f, 1.00f);
        } else {
            float u = (float) (elapsedNs - HOLD_END_NS) / (float) (TOTAL_NS - HOLD_END_NS);
            float rem = 1.0f - u;
            opacityMultiplier = PEAK_ALPHA_MULTIPLIER * (rem * rem * (0.65f + 0.35f * rem));
            popScale = 1.00f;
        }

        if (opacityMultiplier <= 0.02f) {
            return;
        }

        float scale = Math.max(0.1f, MeowyConfig.get().crosshairScale)
            * CustomCrosshairRenderer.BASE_ASSET_SCALE
            * popScale;

        graphics.pose().pushMatrix();
        graphics.pose().translate(centerX, centerY);
        graphics.pose().scale(scale, scale);

        for (int i = 0; i < HITMARKER_SPANS.length; i++) {
            int[] span = HITMARKER_SPANS[i];
            int scaledAlpha = Mth.clamp(Math.round(span[4] * opacityMultiplier), 0, 204);
            if (scaledAlpha <= 3) {
                continue;
            }
            graphics.fill(span[0], span[1], span[2], span[3], ARGB.color(scaledAlpha, span[5], span[6], span[7]));
        }

        graphics.pose().popMatrix();
    }
}
