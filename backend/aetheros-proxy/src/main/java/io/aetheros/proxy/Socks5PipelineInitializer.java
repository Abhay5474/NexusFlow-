package io.aetheros.proxy;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5ServerEncoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.concurrent.TimeUnit;

/**
 * Builds the per-connection Netty pipeline. Phase decoders are added in
 * order; each phase handler removes its decoder once that phase completes
 * (see {@code ARCHITECTURE.md §3}).
 */
public final class Socks5PipelineInitializer extends ChannelInitializer<SocketChannel> {

    private final Socks5RequestRouter router;

    public Socks5PipelineInitializer(Socks5RequestRouter router) {
        this.router = router;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ch.pipeline()
          .addLast("idle",          new IdleStateHandler(60, 60, 120, TimeUnit.SECONDS))
          .addLast("trace",         new LoggingHandler(LogLevel.TRACE))
          .addLast("socks5encoder", Socks5ServerEncoder.DEFAULT)
          .addLast("phase1-decode", new Socks5InitialRequestDecoder())
          .addLast("phase1",        new Socks5MethodNegotiationHandler())
          // phase2 (auth) installed dynamically iff method == USER/PASS
          // phase3 (request) installed by phase1 on success
          // phase4..6 installed by phase3 handler
          .addLast("router",        router);
    }
}
