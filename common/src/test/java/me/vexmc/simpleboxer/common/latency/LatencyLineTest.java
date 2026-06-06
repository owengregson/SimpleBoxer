package me.vexmc.simpleboxer.common.latency;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LatencyLineTest {

    private static long ms(long millis) {
        return TimeUnit.MILLISECONDS.toNanos(millis);
    }

    @Test
    void entriesMatureExactlyAtTheDelay() {
        LatencyLine<String> line = new LatencyLine<>();
        line.offer("hit", ms(1000));
        assertTrue(line.drain(ms(1049), ms(50)).isEmpty(), "49ms old: not yet");
        assertEquals(List.of("hit"), line.drain(ms(1050), ms(50)), "50ms old: matured");
        assertTrue(line.drain(ms(2000), ms(50)).isEmpty(), "drained once only");
    }

    @Test
    void orderIsPreserved() {
        LatencyLine<Integer> line = new LatencyLine<>();
        line.offer(1, ms(0));
        line.offer(2, ms(10));
        line.offer(3, ms(20));
        assertEquals(List.of(1, 2, 3), line.drain(ms(100), ms(25)));
    }

    @Test
    void zeroDelayMaturesImmediately() {
        LatencyLine<String> line = new LatencyLine<>();
        line.offer("now", ms(500));
        assertEquals(List.of("now"), line.drain(ms(500), 0L));
    }

    @Test
    void partialMaturityLeavesTheYoung() {
        LatencyLine<Integer> line = new LatencyLine<>();
        line.offer(1, ms(0));
        line.offer(2, ms(40));
        assertEquals(List.of(1), line.drain(ms(50), ms(50)), "only the 50ms-old entry");
        assertEquals(List.of(2), line.drain(ms(90), ms(50)), "the second follows later");
    }

    @Test
    void raisingTheDelayHoldsTheBacklog() {
        LatencyLine<String> line = new LatencyLine<>();
        line.offer("a", ms(0));
        assertTrue(line.drain(ms(60), ms(100)).isEmpty(), "100ms delay holds a 60ms entry");
        assertEquals(List.of("a"), line.drain(ms(100), ms(100)));
    }

    @Test
    void drainLatestSkipsStaleSnapshots() {
        LatencyLine<String> line = new LatencyLine<>();
        line.offer("t0", ms(0));
        line.offer("t1", ms(50));
        line.offer("t2", ms(100));
        assertEquals("t1", line.drainLatest(ms(120), ms(50)), "newest matured wins");
        assertEquals("t2", line.drainLatest(ms(200), ms(50)));
        assertNull(line.drainLatest(ms(201), ms(50)), "nothing left");
    }
}
