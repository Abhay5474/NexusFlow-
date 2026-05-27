package io.aetheros.proxy;

import io.aetheros.bandshifter.ClassDistribution;
import io.aetheros.bandshifter.HeuristicClassifier;
import io.aetheros.bandshifter.Shaper;
import io.aetheros.bandshifter.TrafficClass;
import io.aetheros.bandshifter.TrafficClassifier;
import io.aetheros.chameleon.ClientHelloPeekHandler;
import io.aetheros.core.dns.DnsAnswer;
import io.aetheros.core.dns.DnsResolverPort;
import io.aetheros.core.forensics.ForensicsEvent;
import io.aetheros.core.forensics.ForensicsEventPort;
import io.aetheros.core.geo.GeoLookupPort;
import io.aetheros.core.geo.GeoPoint;
import io.aetheros.core.policy.RoutingContext;
import io.aetheros.core.policy.RoutingDecision;
import io.aetheros.core.policy.RoutingPolicy;
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
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@ChannelHandler.Sharable
public final class Socks5RequestRouter extends SimpleChannelInboundHandler<Socks5CommandRequest> {

    private final DnsResolverPort dns;
    private final UpstreamConnector connector;
    private final Shaper shaper;
    private final ForensicsEventPort forensics;
    private final GeoLookupPort geo;
    private final Supplier<RoutingPolicy> policySupplier;
    private final ClassDistribution distribution;

    public Socks5RequestRouter(DnsResolverPort dns,
                               UpstreamConnector connector,
                               Shaper shaper,
                               ForensicsEventPort forensics,
                               GeoLookupPort geo,
                               Supplier<RoutingPolicy> policySupplier,
                               ClassDistribution distribution) {
        this.dns = dns;
        this.connector = connector;
        this.shaper = shaper;
        this.forensics = forensics;
        this.geo = geo;
        this.policySupplier = policySupplier;
        this.distribution = distribution;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Socks5CommandRequest req) {
        String connId = UUID.randomUUID().toString();

        if (req.type() != Socks5CommandType.CONNECT) {
            reply(ctx, Socks5CommandStatus.COMMAND_UNSUPPORTED, req.dstAddrType(), req.dstAddr(), req.dstPort());
            return;
        }

        // ── Zero-trust policy gate ───────────────────────────────────────
        RoutingDecision decision = policySupplier.get().evaluate(
                new RoutingContext(req.dstAddr(), req.dstPort(), LocalTime.now()));
        if (decision instanceof RoutingDecision.Deny d) {
            forensics.emit(new ForensicsEvent(
                    Instant.now(), connId, ForensicsEvent.Stage.SOCKS_HANDSHAKE,
                    "policy-deny reason=" + d.reason(),
                    Map.of("domain", req.dstAddr(), "port", req.dstPort(), "reason", d.reason())));
            reply(ctx, Socks5CommandStatus.FORBIDDEN, req.dstAddrType(), req.dstAddr(), req.dstPort());
            return;
        }

        resolveDestination(ctx, connId, req).whenComplete((addr, ex) -> {
            if (ex != null || addr == null) {
                ctx.executor().execute(() ->
                        reply(ctx, Socks5CommandStatus.HOST_UNREACHABLE,
                              req.dstAddrType(), req.dstAddr(), req.dstPort()));
                return;
            }
            emitGeo(connId, addr, req);
            ctx.executor().execute(() -> dial(ctx, connId, req, addr, decision));
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
                      InetAddress addr,
                      RoutingDecision decision) {
        Channel clientCh = clientCtx.channel();
        InetSocketAddress dest = new InetSocketAddress(addr, req.dstPort());
        AtomicReference<String> sniRef = new AtomicReference<>();

        ChannelInitializer<NioSocketChannel> upstreamInit = new ChannelInitializer<>() {
            @Override protected void initChannel(NioSocketChannel ch) {
                ch.pipeline().addLast("up-writability", new WritabilityResumer(clientCh));
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
                    onConnected(clientCtx, connId, req, c.channel(), c.lane().id(), sniRef, decision));
        });
    }

    private void onConnected(ChannelHandlerContext clientCtx,
                             String connId,
                             Socks5CommandRequest req,
                             Channel upstream,
                             int laneId,
                             AtomicReference<String> sniRef,
                             RoutingDecision decision) {
        forensics.emit(new ForensicsEvent(
                Instant.now(), connId, ForensicsEvent.Stage.LANE_PICK,
                "lane=" + laneId + (decision instanceof RoutingDecision.PinLane ? " pinned" : ""),
                Map.of("laneId", laneId, "dst", req.dstAddr() + ":" + req.dstPort())));

        clientCtx.writeAndFlush(new DefaultSocks5CommandResponse(
                Socks5CommandStatus.SUCCESS, req.dstAddrType(), req.dstAddr(), req.dstPort()));

        var p = clientCtx.pipeline();
        p.remove("phase3-decode");
        p.remove("router");
        p.remove("socks5encoder");

        TrafficClass initialClass = TrafficClassifier.classify(req.dstPort(), null);
        HeuristicClassifier classifier = new HeuristicClassifier(req.dstPort(), null);
        if (distribution != null) distribution.incFlow(initialClass);

        p.addLast("chameleon", new ClientHelloPeekHandler((cctx, sni) ->
                sni.ifPresent(s -> {
                    sniRef.set(s);
                    // Re-classify with SNI context.
                    HeuristicClassifier withSni = new HeuristicClassifier(req.dstPort(), s);
                    forensics.emit(new ForensicsEvent(
                            Instant.now(), connId, ForensicsEvent.Stage.TLS_PEEK,
                            "sni=" + s + " class=" + withSni.current(),
                            Map.of("sni", s, "port", req.dstPort(), "class", withSni.current().name())));
                })));

        p.addLast("client-writability", new WritabilityResumer(upstream));
        p.addLast("relay-out", new RelayHandler(
                upstream, shaper, classifier, distribution, forensics, connId));

        upstream.pipeline().addLast("relay-in", new RelayHandler(
                clientCtx.channel(), shaper, classifier, distribution, forensics, connId));

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

    private void emitGeo(String connId, InetAddress addr, Socks5CommandRequest req) {
        if (geo == null) return;
        GeoPoint pt = geo.lookup(addr).orElse(GeoPoint.UNKNOWN);
        var tags = new HashMap<String, Object>();
        tags.put("ip", addr.getHostAddress());
        tags.put("lat", pt.latitude());
        tags.put("lon", pt.longitude());
        tags.put("country", pt.country());
        tags.put("city", pt.city());
        tags.put("domain", req.dstAddr());
        tags.put("port", req.dstPort());
        forensics.emit(new ForensicsEvent(
                Instant.now(), connId, ForensicsEvent.Stage.DNS,
                "geo=" + pt.country() + (pt.city().isEmpty() ? "" : "/" + pt.city()),
                tags));
    }

    private void reply(ChannelHandlerContext ctx, Socks5CommandStatus status,
                       Socks5AddressType atype, String addr, int port) {
        ctx.writeAndFlush(new DefaultSocks5CommandResponse(status, atype, addr, port));
        if (status != Socks5CommandStatus.SUCCESS) ctx.close();
    }

    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { ctx.close(); }
}
