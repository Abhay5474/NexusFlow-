package io.aetheros.proxy;

import io.aetheros.bandshifter.Shaper;
import io.aetheros.bandshifter.TrafficClass;
import io.aetheros.bandshifter.TrafficClassifier;
import io.aetheros.chameleon.ClientHelloPeekHandler;
import io.aetheros.core.dns.DnsAnswer;
import io.aetheros.core.dns.DnsResolverPort;
import io.aetheros.core.forensics.ForensicsEvent;
import io.aetheros.core.forensics.ForensicsEventPort;
import io.aetheros.nexus.UpstreamConnector;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.socksx.v5.DefaultSocks5CommandResponse;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequest;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import io.netty.handler.codec.socksx.v5.Socks5CommandType;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SOCKS5 phase 3–6 handler: parses CONNECT, resolves DNS via Sentinel,
 * connects upstream via Nexus, sends REPLY, and stitches the two channels
 * together with {@link RelayHandler} + {@link WritabilityResumer}.
 *
 * <p>Sharable: all per-connection state is captured in the request itself
 * and the resulting handler instances; the router is stateless.
 */
@ChannelHandler.Sharable
public final class Socks5RequestRouter extends SimpleChannelInboundHandler<Socks5CommandRequest> {

    private final DnsResolverPort dns;
    private final UpstreamConnector connector;
    private final Shaper shaper;
    private final ForensicsEventPort forensics;

    public Socks5RequestRouter(DnsResolverPort dns,
                               UpstreamConnector connector,
                               Shaper shaper,
                               ForensicsEventPort forensics) {
        this.dns = dns;
        this.connector = connector;
        this.shaper = shaper;
        this.forensics = forensics;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Socks5CommandRequest req) {
        String connId = UUID.randomUUID().toString();

        if (req.type() != Socks5CommandType.CONNECT) {
            reply(ctx, Socks5CommandStatus.COMMAND_UNSUPPORTED, req.dstAddrType(), req.dstAddr(), req.dstPort());
            return;
        }

        resolveDestination(ctx, connId, req).whenComplete((addr, ex) -> {
            if (ex != null || addr == null) {
                ctx.executor().execute(() ->
                        reply(ctx, Socks5CommandStatus.HOST_UNREACHABLE,
                              req.dstAddrType(), req.dstAddr(), req.dstPort()));
                return;
            }
            ctx.executor().execute(() -> dial(ctx, connId, req, addr));
        });
    }

    private java.util.concurrent.CompletionStage<InetAddress> resolveDestination(
            ChannelHandlerContext ctx, String connId, Socks5CommandRequest req) {
        if (req.dstAddrType() == Socks5AddressType.DOMAIN) {
            return dns.resolve(req.dstAddr()).thenApply(a -> {
                emitDns(connId, a);
                return a.addresses().get(0);
            });
        }
        try {
            return java.util.concurrent.CompletableFuture.completedFuture(InetAddress.getByName(req.dstAddr()));
        } catch (Exception e) {
            return java.util.concurrent.CompletableFuture.failedFuture(e);
        }
    }

    private void dial(ChannelHandlerContext clientCtx,
                      String connId,
                      Socks5CommandRequest req,
                      InetAddress addr) {
        Channel clientCh = clientCtx.channel();
        InetSocketAddress dest = new InetSocketAddress(addr, req.dstPort());

        AtomicReference<String> sniRef = new AtomicReference<>();

        ChannelInitializer<NioSocketChannel> upstreamInit = new ChannelInitializer<>() {
            @Override protected void initChannel(NioSocketChannel ch) {
                ch.pipeline().addLast("up-writability", new WritabilityResumer(clientCh));
                // Relay handler installed once we know SNI / traffic class.
            }
        };

        connector.connect(clientCh.eventLoop(), dest, upstreamInit).whenComplete((c, ex) -> {
            if (ex != null) {
                clientCtx.executor().execute(() ->
                        reply(clientCtx, Socks5CommandStatus.NETWORK_UNREACHABLE,
                              req.dstAddrType(), req.dstAddr(), req.dstPort()));
                return;
            }
            clientCtx.executor().execute(() ->
                    onConnected(clientCtx, connId, req, c.channel(), c.lane().id(), sniRef));
        });
    }

    private void onConnected(ChannelHandlerContext clientCtx,
                             String connId,
                             Socks5CommandRequest req,
                             Channel upstream,
                             int laneId,
                             AtomicReference<String> sniRef) {
        forensics.emit(new ForensicsEvent(
                Instant.now(), connId, ForensicsEvent.Stage.LANE_PICK,
                "lane=" + laneId,
                Map.of("laneId", laneId, "dst", req.dstAddr() + ":" + req.dstPort())));

        // 1. Send SOCKS5 REPLY (success) before swapping the pipeline to relay mode.
        clientCtx.writeAndFlush(new DefaultSocks5CommandResponse(
                Socks5CommandStatus.SUCCESS,
                req.dstAddrType(),
                req.dstAddr(),
                req.dstPort()));

        // 2. Pipeline mutation: strip SOCKS codecs, install Chameleon peek and Relay handler.
        var p = clientCtx.pipeline();
        p.remove("phase3-decode");
        p.remove("router");
        p.remove("socks5encoder");

        TrafficClass initialClass = TrafficClassifier.classify(req.dstPort(), null);

        // Chameleon: passive SNI peek for observability + re-classification on first flight.
        p.addLast("chameleon", new ClientHelloPeekHandler((cctx, sni) -> {
            sni.ifPresent(s -> {
                sniRef.set(s);
                forensics.emit(new ForensicsEvent(
                        Instant.now(), connId, ForensicsEvent.Stage.TLS_PEEK,
                        "sni=" + s,
                        Map.of("sni", s, "port", req.dstPort())));
            });
        }));

        // 3. Client → upstream relay.
        p.addLast("client-writability", new WritabilityResumer(upstream));
        p.addLast("relay-out", new RelayHandler(
                upstream, shaper, initialClass, forensics, connId));

        // 4. Upstream → client relay (mirror).
        upstream.pipeline().addLast("relay-in", new RelayHandler(
                clientCtx.channel(), shaper, initialClass, forensics, connId));

        // 5. Kick reads on both sides — auto-read was off on upstream.
        upstream.config().setAutoRead(true);
        upstream.read();
        clientCtx.channel().read();
    }

    private void emitDns(String connId, DnsAnswer a) {
        var tags = new HashMap<String, Object>();
        tags.put("provider", a.provider());
        tags.put("latencyMs", a.latency().toMillis());
        forensics.emit(new ForensicsEvent(
                Instant.now(), connId, ForensicsEvent.Stage.DNS,
                "winner=" + a.provider(), tags));
    }

    private void reply(ChannelHandlerContext ctx, Socks5CommandStatus status,
                       Socks5AddressType atype, String addr, int port) {
        ctx.writeAndFlush(new DefaultSocks5CommandResponse(status, atype, addr, port));
        if (status != Socks5CommandStatus.SUCCESS) ctx.close();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { ctx.close(); }
}
