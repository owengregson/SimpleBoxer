package me.vexmc.simpleboxer.nms;

import org.jetbrains.annotations.NotNull;

/**
 * One clientbound packet the server wrote to a boxer's connection, stamped
 * at write time. The brain drains these through its perception latency line
 * — a boxer "receives" its knockback exactly one simulated one-way later,
 * like a real client would.
 */
public record CapturedPacket(long nanos, @NotNull Object packet) {}
