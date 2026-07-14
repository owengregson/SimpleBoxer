package me.vexmc.simpleboxer.common.knockback;

import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * The single, deterministic authority for what velocity a boxer's client
 * physics receives — replacing three historically racy paths (the polled
 * {@code hurtMarked} flag, the own-id {@code SetEntityMotion} echo, and the
 * {@code EntityKnockbackEvent} feed) with one ranked, deduplicated intake.
 *
 * <p>The server can describe the same knock through more than one channel in the
 * same tick (vanilla motion echo, Paper's knockback event, a combat plugin's
 * {@code PlayerVelocityEvent}, StarEnchants' {@code setVelocity}); each is a
 * REPLACE of all three axes, and only the most authoritative should land.
 * Channels are ranked; per server tick the resolver applies exactly one REPLACE
 * (the winner) plus the summed explosion ADD lane. Every sample ages through the
 * same perception-latency the rest of the brain uses, so a laggy boxer starts
 * flying back {@code ping/2} after the hit — like a real client.</p>
 *
 * <p>Pure and owning-thread; {@link #offer} may be pre-staged from listeners on
 * the owning region thread, then {@link #resolve} drains on the brain tick.</p>
 */
public final class KnockbackResolver {

    /**
     * REPLACE authority, lowest to highest. A higher-ranked sample for the same
     * server tick wins; ties break to the later capture time.
     */
    public enum Channel {
        /** Polled {@code Entity.hurtMarked} — the last-resort fallback. */
        HURT_MARKED(0),
        /** The tracker's own {@code SetEntityMotion} echo for the boxer's id. */
        MOTION_ECHO(1),
        /** Paper's {@code EntityKnockbackEvent} — full pre-tick melee knock. */
        MELEE_KB(2),
        /** {@code PlayerVelocityEvent} — the final value a plugin (Mental, StarEnchants) applies. */
        PLAYER_VELOCITY(3);

        final int rank;

        Channel(int rank) {
            this.rank = rank;
        }
    }

    /** Where a resolved velocity lands — the client physics integrator. */
    public interface PhysicsSink {
        /** Replace all three motion axes (a knockback/velocity packet). */
        void applyVelocity(double x, double y, double z);

        /** Add to motion (explosion knockback). */
        void addVelocity(double x, double y, double z);
    }

    private record Sample(int rank, boolean explosion,
            double x, double y, double z, long serverTick, long nanos) {}

    private static final int MAX_PENDING = 256;

    private final List<Sample> pending = new ArrayList<>();

    /** Stage a REPLACE sample on {@code channel}, captured at {@code nanos} for {@code serverTick}. */
    public void offer(@NotNull Channel channel, double x, double y, double z,
            long serverTick, long nanos) {
        add(new Sample(channel.rank, false, x, y, z, serverTick, nanos));
    }

    /** Stage an explosion knockback (ADD lane) for {@code serverTick}. */
    public void offerExplosion(double x, double y, double z, long serverTick, long nanos) {
        add(new Sample(-1, true, x, y, z, serverTick, nanos));
    }

    private void add(Sample sample) {
        if (pending.size() >= MAX_PENDING) {
            pending.remove(0); // shed the oldest; a runaway producer never grows unbounded
        }
        pending.add(sample);
    }

    /**
     * Applies every sample that has aged past {@code oneWayNanos} and is not held
     * back by the straddle grace, coalesced into at most one REPLACE (the winner
     * across all ready ticks: newest server tick, then highest rank, then latest
     * capture) plus one summed explosion ADD.
     *
     * <p>Grace: a server tick's bucket is deferred while any same-or-earlier tick
     * sample is still maturing, so a higher-rank straggler for the same hit
     * (offered a hair later) is never missed by applying too early.</p>
     */
    public void resolve(long now, long oneWayNanos, @NotNull PhysicsSink sink) {
        if (pending.isEmpty()) {
            return;
        }
        long minImmatureTick = Long.MAX_VALUE;
        List<Sample> matured = new ArrayList<>();
        List<Sample> keep = new ArrayList<>();
        for (Sample sample : pending) {
            if (now - sample.nanos >= oneWayNanos) {
                matured.add(sample);
            } else {
                keep.add(sample);
                minImmatureTick = Math.min(minImmatureTick, sample.serverTick);
            }
        }

        Sample bestReplace = null;
        double ex = 0.0;
        double ey = 0.0;
        double ez = 0.0;
        boolean hasExplosion = false;
        for (Sample sample : matured) {
            if (sample.serverTick >= minImmatureTick) {
                keep.add(sample); // defer: an earlier/same-tick straggler is still aging
                continue;
            }
            if (sample.explosion) {
                ex += sample.x;
                ey += sample.y;
                ez += sample.z;
                hasExplosion = true;
            } else if (bestReplace == null || wins(sample, bestReplace)) {
                bestReplace = sample;
            }
        }

        pending.clear();
        pending.addAll(keep);

        if (bestReplace != null) {
            sink.applyVelocity(bestReplace.x, bestReplace.y, bestReplace.z);
        }
        if (hasExplosion) {
            sink.addVelocity(ex, ey, ez);
        }
    }

    /** A REPLACE sample beats the incumbent by newer tick, then rank, then capture time. */
    private static boolean wins(Sample candidate, Sample incumbent) {
        if (candidate.serverTick != incumbent.serverTick) {
            return candidate.serverTick > incumbent.serverTick;
        }
        if (candidate.rank != incumbent.rank) {
            return candidate.rank > incumbent.rank;
        }
        return candidate.nanos > incumbent.nanos;
    }

    /** Drop all staged samples (despawn/respawn). */
    public void clear() {
        pending.clear();
    }
}
