package me.vexmc.simpleboxer.common.brain;

import org.jetbrains.annotations.NotNull;

/**
 * A {@link Goal} that is also a multi-tick state machine — a rod poke, a
 * splash-pot heal, a blockhit rhythm, an eat. It stores its phase in
 * {@link BrainMemory} (keyed by {@link #id()}) and, via {@link Intent}'s
 * {@code ActionIntent}, drives slot swaps and item use across ticks. Whether it
 * can be interrupted mid-sequence is its own call.
 */
public interface Routine extends Goal {

    /** May a higher-utility goal pre-empt this routine mid-sequence? */
    boolean interruptible();

    /**
     * True while the routine is partway through its sequence and wants to keep
     * control (the arbiter grants it commitment beyond raw utility). Read from
     * {@link BrainMemory}; false when idle/complete.
     */
    default boolean inProgress(@NotNull BrainMemory memory) {
        return false;
    }
}
