package me.vexmc.simpleboxer.common.knockback;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnockbackResolverTest {

    /** Records what the physics integrator would receive. */
    private static final class Recorder implements KnockbackResolver.PhysicsSink {
        record Op(String kind, double x, double y, double z) {}

        final List<Op> ops = new ArrayList<>();

        @Override
        public void applyVelocity(double x, double y, double z) {
            ops.add(new Op("replace", x, y, z));
        }

        @Override
        public void addVelocity(double x, double y, double z) {
            ops.add(new Op("add", x, y, z));
        }
    }

    private static final long ONE_WAY = 100L;

    @Test
    void highestRankWinsForOneServerTick() {
        KnockbackResolver resolver = new KnockbackResolver();
        resolver.offer(KnockbackResolver.Channel.HURT_MARKED, 9, 9, 9, 5, 1000);
        resolver.offer(KnockbackResolver.Channel.MELEE_KB, 5, 5, 5, 5, 1000);
        resolver.offer(KnockbackResolver.Channel.PLAYER_VELOCITY, 1, 2, 3, 5, 1000);
        Recorder sink = new Recorder();
        resolver.resolve(1000 + ONE_WAY, ONE_WAY, sink);
        assertEquals(1, sink.ops.size(), "exactly one REPLACE lands");
        assertEquals(new Recorder.Op("replace", 1, 2, 3), sink.ops.get(0),
                "the player-velocity channel outranks melee and hurt-marked");
    }

    @Test
    void immatureSamplesAreHeld() {
        KnockbackResolver resolver = new KnockbackResolver();
        resolver.offer(KnockbackResolver.Channel.MELEE_KB, 1, 0, 0, 5, 1000);
        Recorder sink = new Recorder();
        resolver.resolve(1000 + ONE_WAY - 1, ONE_WAY, sink);
        assertTrue(sink.ops.isEmpty(), "a sample younger than the one-way delay is not applied");
        resolver.resolve(1000 + ONE_WAY, ONE_WAY, sink);
        assertEquals(1, sink.ops.size(), "it applies once matured");
    }

    @Test
    void graceHoldsForASameTickHigherRankStraggler() {
        KnockbackResolver resolver = new KnockbackResolver();
        resolver.offer(KnockbackResolver.Channel.MELEE_KB, 1, 0, 0, 5, 1000);
        resolver.offer(KnockbackResolver.Channel.PLAYER_VELOCITY, 2, 0, 0, 5, 1050);
        Recorder sink = new Recorder();
        // melee matured, player-velocity not yet — the whole tick-5 bucket defers.
        resolver.resolve(1105, ONE_WAY, sink);
        assertTrue(sink.ops.isEmpty(), "the bucket waits for the same-tick straggler");
        // both matured — the higher-rank straggler wins.
        resolver.resolve(1155, ONE_WAY, sink);
        assertEquals(1, sink.ops.size());
        assertEquals(new Recorder.Op("replace", 2, 0, 0), sink.ops.get(0));
    }

    @Test
    void explosionsAddAlongsideTheReplace() {
        KnockbackResolver resolver = new KnockbackResolver();
        resolver.offer(KnockbackResolver.Channel.MELEE_KB, 1, 0, 0, 7, 2000);
        resolver.offerExplosion(0.1, 0.2, 0.3, 7, 2000);
        resolver.offerExplosion(0.1, 0.0, 0.1, 7, 2000);
        Recorder sink = new Recorder();
        resolver.resolve(2000 + ONE_WAY, ONE_WAY, sink);
        assertEquals(2, sink.ops.size());
        assertTrue(sink.ops.contains(new Recorder.Op("replace", 1, 0, 0)));
        assertTrue(sink.ops.stream().anyMatch(op -> op.kind().equals("add")
                && Math.abs(op.x() - 0.2) < 1e-9 && Math.abs(op.y() - 0.2) < 1e-9
                && Math.abs(op.z() - 0.4) < 1e-9), "explosion knockbacks sum into one ADD");
    }

    @Test
    void explosionOnlyTickAppliesJustTheAdd() {
        KnockbackResolver resolver = new KnockbackResolver();
        resolver.offerExplosion(0.5, 0.1, 0.0, 3, 500);
        Recorder sink = new Recorder();
        resolver.resolve(500 + ONE_WAY, ONE_WAY, sink);
        assertEquals(1, sink.ops.size());
        assertEquals("add", sink.ops.get(0).kind());
    }
}
