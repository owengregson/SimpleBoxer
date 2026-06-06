package me.vexmc.simpleboxer.nms;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import java.util.function.Consumer;
import org.jetbrains.annotations.NotNull;

/**
 * The boxer connection's outbound terminus. Everything the server writes is
 * stamped and handed to the sink, then completed instantly — nothing may
 * reach the EmbeddedChannel's internal buffer. That buffer is a
 * single-threaded test construct, and the live server writes to player
 * connections from several threads (chunk streaming mid-tick); letting
 * writes through corrupts it (null-promise NPEs) and can wedge the main
 * thread inside the send loop — the hard-won lesson from Mental's tester.
 *
 * <p>The sink runs on whatever thread wrote (main, netty, chunk loader):
 * it must only enqueue into a concurrent structure and never touch live
 * server state.</p>
 */
final class OutboundCapture extends ChannelOutboundHandlerAdapter {

    private final Consumer<CapturedPacket> sink;

    OutboundCapture(@NotNull Consumer<CapturedPacket> sink) {
        this.sink = sink;
    }

    @Override
    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
        try {
            sink.accept(new CapturedPacket(System.nanoTime(), message));
        } catch (Throwable sinkFailure) {
            // The capture is observability for the brain; a decode hiccup
            // must never break the server's send path.
        }
        ReferenceCountUtil.release(message);
        if (promise != null && !promise.isVoid()) {
            promise.trySuccess();
        }
    }

    @Override
    public void flush(ChannelHandlerContext context) {}
}
