package me.vexmc.simpleboxer.common.latency;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.jetbrains.annotations.NotNull;

/**
 * One direction of simulated latency: a timestamped FIFO whose entries
 * mature {@code delay} after they were offered. A boxer's RTT splits into
 * two lines — perception (world state and inbound packets age before the
 * brain sees them) and action (decided inputs age before the server feels
 * them) — which together quantize onto ticks exactly like real netty
 * arrival.
 *
 * <p>Producers may be any thread (the outbound packet capture runs on
 * server/netty threads); the single consumer is the boxer's brain tick.
 * Lowering the delay mid-flight matures backlog immediately; raising it
 * holds maturity to each entry's original offer time plus the new delay.</p>
 */
public final class LatencyLine<T> {

    private record Entry<T>(long atNanos, T item) {}

    private final ConcurrentLinkedQueue<Entry<T>> queue = new ConcurrentLinkedQueue<>();

    public void offer(@NotNull T item, long nowNanos) {
        queue.add(new Entry<>(nowNanos, item));
    }

    /** All entries at least {@code delayNanos} old, oldest first. */
    public @NotNull List<T> drain(long nowNanos, long delayNanos) {
        List<T> matured = new ArrayList<>();
        Entry<T> head;
        while ((head = queue.peek()) != null && nowNanos - head.atNanos() >= delayNanos) {
            queue.poll();
            matured.add(head.item());
        }
        return matured;
    }

    /** The newest matured entry only, discarding older backlog (snapshots). */
    public T drainLatest(long nowNanos, long delayNanos) {
        List<T> matured = drain(nowNanos, delayNanos);
        return matured.isEmpty() ? null : matured.get(matured.size() - 1);
    }

    public void clear() {
        queue.clear();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }
}
