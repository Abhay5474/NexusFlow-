package io.aetheros.proxy;

import io.aetheros.bandshifter.Shaper;
import io.aetheros.core.dns.DnsAnswer;
import io.aetheros.core.dns.DnsResolverPort;
import io.aetheros.core.forensics.ForensicsEvent;
import io.aetheros.core.forensics.ForensicsEventPort;
import io.aetheros.nexus.LaneManager;
import io.aetheros.nexus.UpstreamConnector;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives an end-to-end SOCKS5 CONNECT against an in-process echo server.
 * Verifies handshake, REPLY, and bidirectional relay.
 */
class Socks5RoundTripIT {

    private Socks5Server proxy;
    private Channel echo;
    private NioEventLoopGroup echoGroup;
    private int echoPort;

    @BeforeEach
    void setUp() throws Exception {
        echoGroup = new NioEventLoopGroup(1);
        var b = new ServerBootstrap()
                .group(echoGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override public void channelRead(ChannelHandlerContext c, Object msg) {
                                c.writeAndFlush(msg);   // echo
                            }
                        });
                    }
                });
        echo = b.bind("127.0.0.1", 0).sync().channel();
        echoPort = ((InetSocketAddress) echo.localAddress()).getPort();

        DnsResolverPort dns = name ->
                CompletableFuture.completedFuture(new DnsAnswer(
                        name, List.of(InetAddress.getLoopbackAddress()),
                        Duration.ofSeconds(60), "test",
                        Duration.ofMillis(1), Instant.now()));

        ForensicsEventPort forensics = new ForensicsEventPort() {
            public void emit(ForensicsEvent e) {}
            public Flux<ForensicsEvent> stream() { return Flux.empty(); }
        };

        var lanes = new LaneManager(2);
        var connector = new UpstreamConnector(lanes);
        var shaper = new Shaper(10_000_000, 1_000_000);

        var router = new Socks5RequestRouter(dns, connector, shaper, forensics);
        var init = new Socks5PipelineInitializer(router);
        proxy = new Socks5Server("127.0.0.1", 0, init);
        ChannelFuture cf = proxy.start().sync();
        // Extract the actual ephemeral port via reflection on the bound channel.
        proxyPort = ((InetSocketAddress) cf.channel().localAddress()).getPort();
    }

    private int proxyPort;

    @AfterEach
    void tearDown() {
        if (proxy != null) proxy.stop();
        if (echo != null) echo.close();
        if (echoGroup != null) echoGroup.shutdownGracefully();
    }

    @Test
    void connectAndEcho() throws Exception {
        try (Socket s = new Socket("127.0.0.1", proxyPort)) {
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();

            // Phase 1: method negotiation
            out.write(new byte[]{0x05, 0x01, 0x00});
            out.flush();
            byte[] m = readN(in, 2);
            assertThat(m).containsExactly(0x05, 0x00);

            // Phase 3: CONNECT 127.0.0.1:echoPort
            byte[] req = new byte[10];
            req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = 0x01; // IPv4
            req[4] = 127; req[5] = 0; req[6] = 0; req[7] = 1;
            req[8] = (byte) ((echoPort >> 8) & 0xff);
            req[9] = (byte) (echoPort & 0xff);
            out.write(req);
            out.flush();

            byte[] resp = readN(in, 10);
            assertThat(resp[0]).isEqualTo((byte) 0x05);
            assertThat(resp[1]).isEqualTo((byte) 0x00);   // SUCCESS

            // Relay: echo
            byte[] payload = "ping".getBytes();
            out.write(payload);
            out.flush();
            byte[] echoed = readN(in, payload.length);
            assertThat(new String(echoed)).isEqualTo("ping");
        }
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int got = 0;
        while (got < n) {
            int r = in.read(b, got, n - got);
            if (r < 0) throw new IOException("EOF");
            got += r;
        }
        return b;
    }

    // Avoid unused-import warnings on ByteBuf/Unpooled if they sneak in via refactors.
    @SuppressWarnings("unused")
    private static ByteBuf neverUsed() { return Unpooled.EMPTY_BUFFER; }

    @SuppressWarnings("unused")
    private CompletionStage<?> neverUsed2() { return CompletableFuture.completedFuture(null); }
}
