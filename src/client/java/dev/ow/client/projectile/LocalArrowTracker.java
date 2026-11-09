package dev.ow.client.projectile;

import dev.meowy.client.MeowyClient;
import dev.ow.client.animation.CassidyAnimator;
import dev.ow.client.animation.PistolAnimator;
import dev.ow.client.skin.BowSkinManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides, once per arrow, whether an {@link AbstractArrow} was fired by the local player.
 *
 * <p>Servers such as Hypixel SkyBlock fire several arrows per shot (Terminator, Juju, ...) and often
 * spawn them without a usable owner, so ownership is resolved like this, in order:
 * <ol>
 *   <li>Owner is a player: authoritative (local player = yes, any other player = no).</li>
 *   <li>Owner is a mob (skeleton, etc.): no.</li>
 *   <li>Otherwise (no owner / unresolved owner / odd owner): a "fresh shot near me" heuristic. It estimates where
 *       the arrow <em>started</em> (current position minus velocity times age) so fast arrows still qualify even if
 *       they have already travelled several blocks when first drawn, and it opens a short volley window after
 *       any confirmed local arrow so every sibling arrow of the same shot qualifies together.</li>
 * </ol>
 * The verdict is cached per entity id so an arrow never flips between laser and arrow mid-flight.
 */
public final class LocalArrowTracker {

    /** An arrow first seen later than this (in ticks) cannot be a fresh shot of ours. */
    private static final int MAX_FIRST_SEEN_TICKS = 10;

    /**
     * Sibling rule: an unowned arrow is part of my volley when one of MY owned arrows spawned right next to it
     * (consecutive-ish entity id, same age, same place). Independent of render order and camera direction.
     */
    private static final int SIBLING_MAX_ID_GAP = 12;
    private static final int SIBLING_MAX_TICK_GAP = 3;
    private static final double SIBLING_MAX_DISTANCE = 12.0;

    /** Fallback rule used when no owned anchor arrow exists and no recent local shot is known. */
    private static final double STRICT_RADIUS = 8.0;
    private static final double STRICT_MIN_DOT = 0.0;

    /** Relaxed fallback rule applied right after a confirmed local arrow. */
    private static final long VOLLEY_WINDOW_MS = 400L;
    private static final double VOLLEY_RADIUS = 12.0;
    private static final double VOLLEY_MIN_DOT = -0.25;

    private static final Map<Integer, Boolean> ARROW_OWNERSHIP_CACHE = new ConcurrentHashMap<>();

    private static volatile long lastLocalShotMs = 0L;
    private static volatile boolean debug = false;

    private LocalArrowTracker() {}

    public static boolean isDebug() {
        return debug;
    }

    public static boolean toggleDebug() {
        debug = !debug;
        return debug;
    }

    public static boolean isLocalPlayerArrow(AbstractArrow arrow) {
        if (arrow == null) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.level == null) {
            return false;
        }

        int arrowId = arrow.getId();
        Boolean cached = ARROW_OWNERSHIP_CACHE.get(arrowId);
        if (cached != null) {
            return cached;
        }

        LocalPlayer me = client.player;
        Entity owner = arrow.getOwner();
        boolean local;
        String reason;

        if (owner instanceof Player player) {
            local = player == me || player.getId() == me.getId() || player.getUUID().equals(me.getUUID());
            reason = local ? "owner is local player" : "owner is another player";
        } else if (owner instanceof Mob) {
            local = false;
            reason = "owner is a mob";
        } else {
            Verdict verdict = evaluateUnowned(client, me, arrow);
            local = verdict.local();
            reason = verdict.reason();
        }

        if (local) {
            lastLocalShotMs = System.currentTimeMillis();
            try {
                PistolAnimator.onLocalArrowShot(); // visual recoil only
            } catch (Throwable t) {
                MeowyClient.logOnce("LocalArrowTracker.pistolShot", t);
            }
            try {
                CassidyAnimator.onLocalArrowShot(); // visual recoil only (ignored unless the Peacemaker is active)
            } catch (Throwable t) {
                MeowyClient.logOnce("LocalArrowTracker.cassidyShot", t);
            }
        }
        ARROW_OWNERSHIP_CACHE.put(arrowId, local);

        if (debug) {
            MeowyClient.LOGGER.info(
                "[OW][arrow] id={} tick={} owner={} -> {} ({})",
                arrowId,
                arrow.tickCount,
                owner == null ? "none" : owner.getClass().getSimpleName(),
                local ? "LASER" : "vanilla",
                reason
            );
        }
        return local;
    }

    private record Verdict(boolean local, String reason) {}

    private static Verdict evaluateUnowned(Minecraft client, LocalPlayer me, AbstractArrow arrow) {
        if (arrow.tickCount > MAX_FIRST_SEEN_TICKS) {
            return new Verdict(false, "first seen too late, tick=" + arrow.tickCount);
        }

        // Strongest signal: an arrow that I own spawned right next to this one (same volley).
        AbstractArrow anchor = findOwnedSiblingAnchor(client.level, me, arrow);
        if (anchor != null) {
            return new Verdict(true, String.format(
                "sibling of my arrow id=%d (id gap %d, tick gap %d, dist %.2f)",
                anchor.getId(),
                Math.abs(anchor.getId() - arrow.getId()),
                Math.abs(anchor.tickCount - arrow.tickCount),
                anchor.position().distanceTo(arrow.position())
            ));
        }

        boolean holdingBow = BowSkinManager.isBow(me.getMainHandItem(), null)
            || BowSkinManager.isBow(me.getOffhandItem(), null);
        if (!holdingBow) {
            return new Verdict(false, "not holding a bow");
        }

        boolean inVolley = (System.currentTimeMillis() - lastLocalShotMs) <= VOLLEY_WINDOW_MS;
        double radius = inVolley ? VOLLEY_RADIUS : STRICT_RADIUS;
        double minDot = inVolley ? VOLLEY_MIN_DOT : STRICT_MIN_DOT;

        Vec3 eye = me.getEyePosition();
        Vec3 pos = arrow.position();
        Vec3 velocity = arrow.getDeltaMovement();
        Vec3 origin = pos.subtract(velocity.scale(arrow.tickCount));

        double nearest = Math.min(pos.distanceTo(eye), origin.distanceTo(eye));
        if (nearest > radius) {
            return new Verdict(false, String.format("too far from me: %.2f > %.1f (volley=%b)", nearest, radius, inVolley));
        }

        double dot = 1.0;
        if (velocity.lengthSqr() > 1.0E-4) {
            Vec3 look = me.getViewVector(1.0f);
            if (look.lengthSqr() > 1.0E-4) {
                dot = velocity.normalize().dot(look.normalize());
            }
            if (dot < minDot) {
                return new Verdict(false, String.format("not flying away from my view: dot %.2f < %.2f (volley=%b)", dot, minDot, inVolley));
            }
        }

        if (isAnotherPlayerCloserShooter(client.level, me, pos, origin, nearest)) {
            return new Verdict(false, "another bow-holding player is closer to the arrow origin");
        }
        return new Verdict(true, String.format("fresh shot near me: dist %.2f, dot %.2f, volley=%b", nearest, dot, inVolley));
    }

    private static AbstractArrow findOwnedSiblingAnchor(ClientLevel level, LocalPlayer me, AbstractArrow arrow) {
        Vec3 pos = arrow.position();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity == arrow || !(entity instanceof AbstractArrow other)) {
                continue;
            }
            if (!(other.getOwner() instanceof Player ownerPlayer)
                || !(ownerPlayer == me || ownerPlayer.getId() == me.getId())) {
                continue;
            }
            if (Math.abs(other.getId() - arrow.getId()) <= SIBLING_MAX_ID_GAP
                && Math.abs(other.tickCount - arrow.tickCount) <= SIBLING_MAX_TICK_GAP
                && other.position().distanceTo(pos) <= SIBLING_MAX_DISTANCE) {
                return other;
            }
        }
        return null;
    }

    private static boolean isAnotherPlayerCloserShooter(
        ClientLevel level,
        LocalPlayer me,
        Vec3 arrowPos,
        Vec3 arrowOrigin,
        double myNearest
    ) {
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof Player other && other != me) {
                boolean otherHoldingBow = BowSkinManager.isBow(other.getMainHandItem(), null)
                    || BowSkinManager.isBow(other.getOffhandItem(), null);
                if (otherHoldingBow) {
                    Vec3 otherEye = other.getEyePosition();
                    double otherNearest = Math.min(arrowPos.distanceTo(otherEye), arrowOrigin.distanceTo(otherEye));
                    if (otherNearest + 0.4 < myNearest) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Classifies every freshly spawned arrow once per call. Normally arrows are classified while they are rendered,
     * but that path is skipped when the laser bullet is switched off; Cassidy's firing detection polls here so it
     * keeps working either way. Already classified arrows are skipped via the ownership cache.
     */
    public static void pollLocalShots(Minecraft client) {
        if (client == null || client.level == null || client.player == null) {
            return;
        }
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity instanceof AbstractArrow arrow
                && arrow.tickCount <= MAX_FIRST_SEEN_TICKS
                && !ARROW_OWNERSHIP_CACHE.containsKey(arrow.getId())) {
                isLocalPlayerArrow(arrow);
            }
        }
    }

    public static void tickCleanup(Minecraft client) {
        if (client == null || client.level == null) {
            ARROW_OWNERSHIP_CACHE.clear();
            return;
        }
        if (ARROW_OWNERSHIP_CACHE.size() > 256) {
            ARROW_OWNERSHIP_CACHE.keySet().removeIf(id -> client.level.getEntity(id) == null);
        }
    }
}
