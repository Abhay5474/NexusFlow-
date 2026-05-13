package io.aetheros.proxy;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

/**
 * Local-bind SOCKS5 entry point. Defaults to {@code 127.0.0.1:1080};
 * LAN binding requires {@code AETHEROS_ALLOW_LAN=1} per the safety contract
 * in {@code ARCHITECTURE.md §8}.
 *
 * <p>Threading: a small boss group accepts; a worker group of size
 * {@code 2 * cores} runs the per-channel pipelines. These are platform
 * threads on purpose — virtual threads on Netty event loops are an
 * anti-pattern (see {@code ARCHITECTURE.md §5}).
 */
public final class Socks5Server {

    private final String bindHost;
    private final int port;
    private final Socks5PipelineInitializer initializer;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public Socks5Server(String bindHost, int port, Socks5PipelineInitializer initializer) {
        this.bindHost = bindHost;
        this.port = port;
        this.initializer = initializer;
    }

    public ChannelFuture start() {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        ServerBootstrap b = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.AUTO_READ, true)
                .handler(new LoggingHandler(LogLevel.INFO))
                .childHandler(initializer);

        ChannelFuture bind = b.bind(bindHost, port);
        serverChannel = bind.channel();
        return bind;
    }

    public void stop() {
        try {
            if (serverChannel != null) serverChannel.close().sync();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {
            if (bossGroup != null) bossGroup.shutdownGracefully();
            if (workerGroup != null) workerGroup.shutdownGracefully();
        }
    }
}
