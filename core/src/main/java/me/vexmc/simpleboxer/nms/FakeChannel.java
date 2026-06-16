package me.vexmc.simpleboxer.nms;

import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;

/**
 * A clientless boxer's netty channel.
 *
 * <p>Functionally an {@link EmbeddedChannel} — packets the server writes are
 * captured in-process instead of being serialised to a socket. The only thing
 * this subclass changes is its <em>simple name</em>.</p>
 *
 * <p>PacketEvents (and ProtocolLib) decide whether to leave a joining player
 * alone by matching {@code channel.getClass().getSimpleName()} against a small
 * set of known fake-channel names. Newer PacketEvents recognises
 * {@code "EmbeddedChannel"}, but that name was only added on 2025-03-23 — older
 * builds, such as the copy shaded inside Grim, recognise only
 * {@code "FakeChannel"} and {@code "SpoofedChannel"}. A plain
 * {@code EmbeddedChannel} therefore slips past the current standalone plugin but
 * is still kicked by Grim's bundled copy ("PacketEvents failed to inject into a
 * channel"). Naming this class {@code FakeChannel} — a name present in every
 * version of {@code FakeChannelUtil} — makes every PacketEvents copy recognise a
 * boxer as fake and skip the kick.</p>
 */
final class FakeChannel extends EmbeddedChannel {

    FakeChannel(ChannelHandler... handlers) {
        super(handlers);
    }
}
