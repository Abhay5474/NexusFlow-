package io.aetheros.proxy;

import io.aetheros.bandshifter.ClassDistribution;
import io.aetheros.bandshifter.HeuristicClassifier;
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
 * Bidirectional pipe with backpressure (auto-read toggling), shaper-aware
 * paced writes, and per-flow heuristic re-classification driven by observed
 * read rates.
 */
public final class RelayHandler extends ChannelInboundHandlerAdapter {

    private final Channel peer;
    private final Shaper shaper;
    private final HeuristicClassifier classifier;
    private final ClassDistribution distribution;
    private final ForensicsEventPort forensics;
    private final String connectionId;
    private long bytesRelayed;

    public RelayHandler(Channel peer,
                        Shaper shaper,
                        HeuristicClassifier classifier,
                        ClassDistribution distribution,
                        ForensicsEventPort forensics,
                        String connectionId) {
        this.peer = peer;
        this.shaper = shaper;
        this.classifier = classifier;
        this.distribution = distribution;
        this.forensics = forensics;
        this.connectionId = connectionId;
    }

    @Override public void channelActive(ChannelHandlerContext ctx) { ctx.channel().read(); }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof ByteBuf buf)) { ctx.fireChannelRead(msg); return; }
        int n = buf.readableBytes();
        bytesRelayed += n;

        if (!peer.isActive()) { buf.release(); ctx.close(); return; }

        TrafficClass cls = classifier.observe(n);
        if (distribution != null) distribution.addBytes(cls, n);

        if (shaper != null && !shaper.admit(cls, n)) {
            ctx.executor().schedule(() -> writeAndContinue(ctx, buf, cls),
                    1, java.util.concurrent.TimeUnit.MILLISECONDS);
            return;
        }
        writeAndContinue(ctx, buf, cls);
    }

    private void writeAndContinue(ChannelHandlerContext ctx, ByteBuf buf, TrafficClass cls) {
        peer.writeAndFlush(buf).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) { ctx.close(); return; }
            if (peer.isWritable()) ctx.channel().read();
        });
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (peer.isActive()) peer.close();
        forensics.emit(new ForensicsEvent(
                Instant.now(), connectionId, ForensicsEvent.Stage.CLOSE,
                "relay-close bytes=" + bytesRelayed,
                Map.of("bytes", bytesRelayed, "class", classifier.current().name())));
    }

    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { ctx.close(); }
}
