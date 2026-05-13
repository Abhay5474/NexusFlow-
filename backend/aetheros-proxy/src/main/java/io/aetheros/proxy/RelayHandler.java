package io.aetheros.proxy;

import io.aetheros.bandshifter.Shaper;
import io.aetheros.bandshifter.TrafficClass;
import io.aetheros.core.forensics.ForensicsEvent;
import io.aetheros.core.forensics.ForensicsEventPort;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.time.Instant;
import java.util.Map;

/**
 * Bidirectional pipe. Each direction has its own {@code RelayHandler}; the
 * "peer" channel is the upstream (for the client-side handler) or the
 * client (for the upstream-side handler).
 *
 * <p>Backpressure: when the peer is not writable, we toggle
 * {@code AUTO_READ=false} on our own channel. The peer's writability listener
 * flips it back on. This is the canonical Netty proxy pattern and is what
 * makes the relay safe under wildly different up/down link speeds.
 *
 * <p>Band-Shifter integration: per-write, the shaper is asked to admit N
 * bytes. If denied, the write is paced — we delay through the event loop's
 * scheduler rather than blocking the thread.
 */
public final class RelayHandler extends ChannelInboundHandlerAdapter {

    private final Channel peer;
    private final Shaper shaper;
    private final TrafficClass trafficClass;
    private final ForensicsEventPort forensics;
    private final String connectionId;
    private long bytesRelayed;

    public RelayHandler(Channel peer,
                        Shaper shaper,
                        TrafficClass trafficClass,
                        ForensicsEventPort forensics,
                        String connectionId) {
        this.peer = peer;
        this.shaper = shaper;
        this.trafficClass = trafficClass;
        this.forensics = forensics;
        this.connectionId = connectionId;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        ctx.channel().read();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof ByteBuf buf)) {
            ctx.fireChannelRead(msg);
            return;
        }
        int n = buf.readableBytes();
        bytesRelayed += n;

        if (!peer.isActive()) {
            buf.release();
            ctx.close();
            return;
        }

        if (shaper != null && !shaper.admit(trafficClass, n)) {
            // Paced — reschedule the same write on the event loop after 1ms.
            final ByteBuf retained = buf;
            ctx.executor().schedule(() -> writeAndContinue(ctx, retained), 1, java.util.concurrent.TimeUnit.MILLISECONDS);
            return;
        }
        writeAndContinue(ctx, buf);
    }

    private void writeAndContinue(ChannelHandlerContext ctx, ByteBuf buf) {
        peer.writeAndFlush(buf).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                ctx.close();
                return;
            }
            if (peer.isWritable()) {
                ctx.channel().read();
            }
            // else: peer.channelWritabilityChanged will resume reads
        });
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (peer.isActive()) peer.close();
        forensics.emit(new ForensicsEvent(
                Instant.now(), connectionId, ForensicsEvent.Stage.CLOSE,
                "relay-close bytes=" + bytesRelayed,
                Map.of("bytes", bytesRelayed, "class", trafficClass.name())));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }
}
