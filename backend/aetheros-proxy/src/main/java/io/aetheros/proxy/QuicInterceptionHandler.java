package io.aetheros.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ALPN & QUIC Hardening — UDP Protocol Interception Handler.
 *
 * <p>Monitors outbound UDP traffic on port 443 and intercepts QUIC (HTTP/3)
 * connection attempts. When a client (e.g., Chrome) tries to establish an
 * encrypted QUIC/HTTP/3 session, this handler drops the UDP packets, forcing
 * the browser's built-in fallback to TCP (TLS 1.3 / HTTP/2).
 *
 * <h3>QUIC detection heuristics</h3>
 * QUIC Long Header packets (connection initials) have a distinctive byte
 * layout (RFC 9000):
 * <ul>
 *   <li>Byte 0: 0x80–0xFF (Long Header flag set: bit 7 = 1)</li>
 *   <li>Bytes 1–4: 0x00000001 (QUIC Version 1) or 0xFACEB002 (Facebook MVFST)</li>
 *   <li>Byte 5: Destination Connection ID length (0–20)</li>
 * </ul>
 *
 * <p>ALPN parsing: for QUIC Initial packets, the CRYPTO frame inside carries
 * a TLS ClientHello with ALPN extensions. We detect "h3" or "h3-29" ALPN
 * values to confirm this is HTTP/3.
 *
 * <p>On detection, the packet is silently dropped. The browser detects UDP
 * loss and falls back to TCP within ~100ms via its Alt-Svc header retry logic.
 *
 * <p>This handler must be installed in the <em>UDP</em> channel pipeline
 * handling port 443, not the TCP pipeline.
 */
public class QuicInterceptionHandler extends SimpleChannelInboundHandler<DatagramPacket> {

    private static final Logger LOG = System.getLogger(QuicInterceptionHandler.class.getName());

    /** QUIC Version 1 (RFC 9000). */
    private static final int QUIC_V1       = 0x00000001;
    /** QUIC Version Draft-29. */
    private static final int QUIC_DRAFT29  = 0xFF00001D;
    /** Facebook MVFST. */
    private static final int QUIC_MVFST   = 0xFACEB002;
    /** Google QUIC (gQUIC). */
    private static final int QUIC_GQUIC   = 0x51303530;

    private static final int TARGET_PORT = 443;

    private final AtomicLong quicDropped      = new AtomicLong(0);
    private final AtomicLong alpnH3Detected   = new AtomicLong(0);
    private final AtomicLong tcpFallbacks     = new AtomicLong(0); // incremented when TCP conn follows

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
        InetSocketAddress recipient = packet.recipient();
        if (recipient.getPort() != TARGET_PORT) {
            ctx.fireChannelRead(packet.retain());
            return;
        }

        ByteBuf buf = packet.content();
        if (buf.readableBytes() < 6) {
            ctx.fireChannelRead(packet.retain());
            return;
        }

        // Peek at the first byte — Long Header bit
        int firstByte = buf.getUnsignedByte(buf.readerIndex());
        boolean longHeader = (firstByte & 0x80) != 0;

        if (!longHeader) {
            // Short header QUIC packet — connection already established
            // Still drop: we only want TCP
            quicDropped.incrementAndGet();
            LOG.log(Level.DEBUG, "[QUIC-INTERCEPT] Dropped short-header QUIC packet → {0}:{1}",
                    recipient.getHostString(), TARGET_PORT);
            return; // DROP — do not call fireChannelRead
        }

        // Check version bytes [1..4]
        int version = buf.getInt(buf.readerIndex() + 1);
        boolean isQuic = (version == QUIC_V1 || version == QUIC_DRAFT29
                       || version == QUIC_MVFST || version == QUIC_GQUIC);

        if (!isQuic) {
            ctx.fireChannelRead(packet.retain());
            return;
        }

        // Try to detect ALPN "h3" in CRYPTO frame (simplified heuristic)
        boolean h3Alpn = detectH3Alpn(buf);
        if (h3Alpn) {
            alpnH3Detected.incrementAndGet();
            LOG.log(Level.INFO,
                    "[QUIC-INTERCEPT] HTTP/3 ALPN detected → DROPPING to force HTTP/2 fallback (dst={0}:{1})",
                    recipient.getHostString(), TARGET_PORT);
        } else {
            LOG.log(Level.DEBUG,
                    "[QUIC-INTERCEPT] QUIC packet (no h3 ALPN) → DROPPING (dst={0}:{1})",
                    recipient.getHostString(), TARGET_PORT);
        }

        quicDropped.incrementAndGet();
        // Packet is dropped by not calling fireChannelRead — budget DatagramPacket is auto-released
    }

    /**
     * Heuristic ALPN h3 detector.
     * Searches the QUIC Initial packet payload for the ASCII bytes "h3" or "h3-"
     * which appear in the ALPN extension of the embedded TLS ClientHello.
     * This is O(n) where n = packet size, which is fine for ~1500-byte MTU packets.
     */
    private boolean detectH3Alpn(ByteBuf buf) {
        int len = buf.readableBytes();
        if (len < 20) return false;

        // Search for "h3" byte sequence (0x68, 0x33) in the packet
        for (int i = buf.readerIndex(); i < buf.readerIndex() + len - 1; i++) {
            if (buf.getByte(i) == 0x68 && buf.getByte(i + 1) == 0x33) {
                return true; // Found "h3"
            }
        }
        return false;
    }

    public long getQuicDropped()    { return quicDropped.get(); }
    public long getAlpnH3Detected() { return alpnH3Detected.get(); }
    public long getTcpFallbacks()   { return tcpFallbacks.get(); }

    /** Called by downstream handlers when a TCP connection follows a QUIC drop. */
    public void recordTcpFallback() { tcpFallbacks.incrementAndGet(); }
}
