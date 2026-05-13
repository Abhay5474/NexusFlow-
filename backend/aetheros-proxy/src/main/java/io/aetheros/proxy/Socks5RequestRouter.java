package io.aetheros.proxy;

import io.aetheros.core.dns.DnsAnswer;
import io.aetheros.core.dns.DnsResolverPort;
import io.aetheros.core.forensics.ForensicsEvent;
import io.aetheros.core.forensics.ForensicsEventPort;
import io.aetheros.core.lane.Lane;
import io.aetheros.core.lane.LaneSelectionStrategy;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequest;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import io.netty.handler.codec.socksx.v5.Socks5CommandType;
import io.netty.handler.codec.socksx.v5.DefaultSocks5CommandResponse;

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SOCKS5 Phase 3–5: parses the CONNECT request, drives Sentinel + Nexus on
 * a virtual thread, then hands the result back to the event loop to emit
 * the REPLY and install the relay handler.
 *
 * <p>Currently sharable but stateless per-message; intentionally minimal —
 * full relay wiring lands with the Nexus module.
 */
@ChannelHandler.Sharable
public final class Socks5RequestRouter extends SimpleChannelInboundHandler<Socks5CommandRequest> {

    private final DnsResolverPort dns;
    private final LaneSelectionStrategy laneStrategy;
    private final List<Lane> lanes;
    private final ForensicsEventPort forensics;

    public Socks5RequestRouter(DnsResolverPort dns,
                               LaneSelectionStrategy laneStrategy,
                               List<Lane> lanes,
                               ForensicsEventPort forensics) {
        this.dns = dns;
        this.laneStrategy = laneStrategy;
        this.lanes = lanes;
        this.forensics = forensics;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Socks5CommandRequest req) {
        String connId = UUID.randomUUID().toString();

        if (req.type() != Socks5CommandType.CONNECT) {
            reply(ctx, Socks5CommandStatus.COMMAND_UNSUPPORTED, req.dstAddrType(), req.dstAddr(), req.dstPort());
            return;
        }

        if (req.dstAddrType() == Socks5AddressType.DOMAIN) {
            dns.resolve(req.dstAddr()).whenComplete((answer, ex) -> {
                if (ex != null || answer == null) {
                    ctx.executor().execute(() ->
                            reply(ctx, Socks5CommandStatus.HOST_UNREACHABLE, req.dstAddrType(), req.dstAddr(), req.dstPort()));
                    return;
                }
                emitDns(connId, answer);
                ctx.executor().execute(() ->
                        onResolved(ctx, connId, req, answer.addresses().get(0)));
            });
        } else {
            try {
                onResolved(ctx, connId, req, InetAddress.getByName(req.dstAddr()));
            } catch (Exception e) {
                reply(ctx, Socks5CommandStatus.HOST_UNREACHABLE, req.dstAddrType(), req.dstAddr(), req.dstPort());
            }
        }
    }

    private void onResolved(ChannelHandlerContext ctx,
                            String connId,
                            Socks5CommandRequest req,
                            InetAddress addr) {
        Lane lane = laneStrategy.pick(lanes);
        forensics.emit(new ForensicsEvent(
                Instant.now(), connId, ForensicsEvent.Stage.LANE_PICK,
                "lane=" + lane.id() + " reason=strategy",
                Map.of("laneId", lane.id(), "score", lane.healthScore())));

        // TODO(nexus-module): open upstream channel on selected lane, install relay handler.
        reply(ctx, Socks5CommandStatus.SUCCESS, req.dstAddrType(), addr.getHostAddress(), req.dstPort());
    }

    private void emitDns(String connId, DnsAnswer a) {
        forensics.emit(new ForensicsEvent(
                Instant.now(), connId, ForensicsEvent.Stage.DNS,
                "winner=" + a.provider(),
                Map.of("provider", a.provider(), "latencyMs", a.latency().toMillis())));
    }

    private void reply(ChannelHandlerContext ctx, Socks5CommandStatus status,
                       Socks5AddressType atype, String addr, int port) {
        ctx.writeAndFlush(new DefaultSocks5CommandResponse(status, atype, addr, port));
        if (status != Socks5CommandStatus.SUCCESS) ctx.close();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }
}
