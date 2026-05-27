package io.aetheros.ironclad;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Netty handler that receives raw IP packets from {@link IroncladAdapter}
 * and parses their headers before forwarding them down the pipeline.
 *
 * Protocol fields extracted:
 * <ul>
 *   <li>IP version (4 or 6)</li>
 *   <li>Protocol number (6=TCP, 17=UDP, 1=ICMP)</li>
 *   <li>Source and destination IPv4 addresses</li>
 *   <li>Source and destination ports (TCP/UDP)</li>
 *   <li>Payload offset and length</li>
 * </ul>
 *
 * Packets that cannot be parsed (too short, unknown version) are silently
 * dropped with a counter increment rather than propagating exceptions.
 */
public class RawIpPacketHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private static final Logger LOG = System.getLogger(RawIpPacketHandler.class.getName());

    private final AtomicLong parsed  = new AtomicLong(0);
    private final AtomicLong dropped = new AtomicLong(0);

    // Minimum IPv4 header length
    private static final int MIN_IPV4_HEADER = 20;
    // Minimum TCP header length
    private static final int MIN_TCP_HEADER  = 20;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf buf) {
        if (buf.readableBytes() < MIN_IPV4_HEADER) {
            dropped.incrementAndGet();
            return;
        }

        // Save reader index so we can reset on error
        buf.markReaderIndex();

        try {
            int versionIhl = buf.readUnsignedByte();
            int version    = (versionIhl >> 4) & 0xF;
            int ihl        = (versionIhl & 0xF) * 4; // header length in bytes

            if (version != 4) {
                // IPv6 and other protocols — pass through untouched
                buf.resetReaderIndex();
                ctx.fireChannelRead(buf.retain());
                return;
            }

            if (ihl < MIN_IPV4_HEADER || buf.readableBytes() < ihl - 1) {
                dropped.incrementAndGet();
                return;
            }

            // Skip DSCP, total length, id, flags/frag, TTL
            buf.skipBytes(8); // dscp(1)+total(2)+id(2)+flags(2)+ttl(1) = 8 after versionIhl

            int protocol  = buf.readUnsignedByte();
            buf.skipBytes(2); // header checksum

            // Source and destination addresses
            byte[] srcAddr = new byte[4];
            byte[] dstAddr = new byte[4];
            buf.readBytes(srcAddr);
            buf.readBytes(dstAddr);

            // Skip any IP options
            int optionsLen = ihl - MIN_IPV4_HEADER;
            if (optionsLen > 0) buf.skipBytes(optionsLen);

            int srcPort = 0, dstPort = 0;
            if ((protocol == 6 || protocol == 17) && buf.readableBytes() >= 4) {
                srcPort = buf.readUnsignedShort();
                dstPort = buf.readUnsignedShort();
            }

            parsed.incrementAndGet();

            // Attach parsed metadata as Netty attributes and re-fire
            buf.resetReaderIndex();
            ctx.channel().attr(IroncladAttributeKeys.SRC_IP).set(ipString(srcAddr));
            ctx.channel().attr(IroncladAttributeKeys.DST_IP).set(ipString(dstAddr));
            ctx.channel().attr(IroncladAttributeKeys.IP_PROTO).set(protocol);
            ctx.channel().attr(IroncladAttributeKeys.SRC_PORT).set(srcPort);
            ctx.channel().attr(IroncladAttributeKeys.DST_PORT).set(dstPort);

            ctx.fireChannelRead(buf.retain());

        } catch (Exception e) {
            dropped.incrementAndGet();
            LOG.log(Level.DEBUG, "[IRONCLAD] Packet parse error: {0}", e.getMessage());
            buf.resetReaderIndex();
        }
    }

    private static String ipString(byte[] addr) {
        return "%d.%d.%d.%d".formatted(
            addr[0] & 0xFF, addr[1] & 0xFF, addr[2] & 0xFF, addr[3] & 0xFF);
    }

    public long getParsed()  { return parsed.get(); }
    public long getDropped() { return dropped.get(); }
}
