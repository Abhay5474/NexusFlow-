package io.aetheros.nexus;

import io.aetheros.core.lane.Lane;
import io.aetheros.nexus.chaos.ChaosController;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Opens upstream TCP connections on behalf of the SOCKS data plane. The
 * client-facing event loop is reused (cheaper than crossing groups, and
 * keeps channel affinity stable for backpressure handling).
 */
public final class UpstreamConnector {

    private final LaneManager lanes;
    private volatile ChaosController chaos;

    public UpstreamConnector(LaneManager lanes) {
        this.lanes = lanes;
    }

    public void setChaos(ChaosController chaos) { this.chaos = chaos; }

    public record Connected(Channel channel, Lane lane, Duration connectLatency) {}

    public CompletableFuture<Connected> connect(EventLoopGroup group,
                                                InetSocketAddress destination,
                                                ChannelInitializer<NioSocketChannel> initializer) {
        Lane lane = lanes.pick();
        long start = System.nanoTime();

        Bootstrap b = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.AUTO_READ, false)   // relay controls reads
                .handler(initializer);

        CompletableFuture<Connected> result = new CompletableFuture<>();
        ChannelFuture cf = b.connect(destination);
        cf.addListener(f -> {
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
            // Chaos: inject artificial latency on the calling event loop without
            // blocking. We delay completion, not the carrier thread.
            ChaosController c = this.chaos;
            long delayMs = (c != null && c.get().enabled())
                    ? c.get().injectedLatency().toMillis() : 0;
            if (f.isSuccess()) {
                lanes.recordSuccess(lane.id(), elapsed);
                Connected ok = new Connected(cf.channel(), lane, elapsed);
                if (delayMs > 0) cf.channel().eventLoop()
                        .schedule(() -> result.complete(ok), delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                else result.complete(ok);
            } else {
                lanes.recordError(lane.id());
                result.completeExceptionally(f.cause());
            }
        });
        return result;
    }
}
