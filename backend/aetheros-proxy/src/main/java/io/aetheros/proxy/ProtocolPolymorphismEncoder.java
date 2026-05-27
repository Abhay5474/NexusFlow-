package io.aetheros.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Project DECEPTICON — Protocol Polymorphism Encoder.
 *
 * <p>Masks outbound traffic by reformatting genuine payload data to mimic
 * alternative, highly-trusted protocol formats. A stateful DPI engine that
 * classifies traffic by protocol shape will misclassify NexusFlow tunnels
 * as harmless, whitelisted protocols.
 *
 * <p>Supported mimicry modes (selected per-connection or per-chunk):
 *
 * <ul>
 *   <li><b>WebRTC_MEDIA</b> — wraps payload in a fake RTP (Real-Time Protocol)
 *       header with plausible SSRC, sequence number, and timestamp fields.
 *       RTP is universally whitelisted in enterprise firewalls for video calls.</li>
 *   <li><b>DNS_TUNNEL</b> — encodes payload as a series of DNS TXT record
 *       response packets. Each chunk becomes a base64-encoded DNS TXT record
 *       inside a synthetic DNS response frame. Useful for egress through
 *       firewalls that only allow outbound UDP/53.</li>
 *   <li><b>WEBSOCKET_FRAMING</b> — wraps payload in WebSocket data frames
 *       (RFC 6455), making tunneled traffic indistinguishable from a normal
 *       WebSocket application session.</li>
 * </ul>
 */
public class ProtocolPolymorphismEncoder extends MessageToMessageEncoder<ByteBuf> {

    private static final Logger LOG = System.getLogger(ProtocolPolymorphismEncoder.class.getName());

    public enum Mode { WEBRTC_MEDIA, DNS_TUNNEL, WEBSOCKET_FRAMING, AUTO }

    private final Mode mode;
    private final AtomicLong morphedChunks = new AtomicLong(0);

    public ProtocolPolymorphismEncoder(Mode mode) {
        this.mode = mode;
    }

    public ProtocolPolymorphismEncoder() {
        this(Mode.AUTO);
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out)
            throws Exception {
        if (!msg.isReadable()) return;

        Mode selected = (mode == Mode.AUTO)
                ? Mode.values()[ThreadLocalRandom.current().nextInt(Mode.values().length - 1)]
                : mode;

        ByteBuf wrapped = switch (selected) {
            case WEBRTC_MEDIA       -> wrapAsRtp(ctx, msg);
            case DNS_TUNNEL         -> wrapAsDns(ctx, msg);
            case WEBSOCKET_FRAMING  -> wrapAsWebSocket(ctx, msg);
            default                 -> msg.retain();
        };

        out.add(wrapped);
        morphedChunks.incrementAndGet();
        LOG.log(Level.TRACE, "[DECEPTICON] Morphed chunk as {0}", selected);
    }

    // -----------------------------------------------------------------------
    // WebRTC / RTP mimicry
    // -----------------------------------------------------------------------

    /**
     * Wraps payload in a fake RTP (RFC 3550) header.
     * Layout: V=2, P=0, X=0, CC=0, M=0, PT=96 (dynamic video), seqno, ts, ssrc, payload
     */
    private ByteBuf wrapAsRtp(ChannelHandlerContext ctx, ByteBuf payload) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        ByteBuf buf = ctx.alloc().buffer(12 + payload.readableBytes());

        // RTP fixed header (12 bytes)
        buf.writeByte(0x80);                        // V=2, P=0, X=0, CC=0
        buf.writeByte(0x60);                        // M=0, PT=96 (H264)
        buf.writeShort(rng.nextInt(0, 0xFFFF));      // sequence number
        buf.writeInt((int)(System.nanoTime() / 90_000)); // timestamp (90kHz clock)
        buf.writeInt(0xDEADBEEF);                   // SSRC (fixed for session)

        buf.writeBytes(payload);
        return buf;
    }

    // -----------------------------------------------------------------------
    // DNS TXT record tunnel mimicry
    // -----------------------------------------------------------------------

    /**
     * Encodes payload as a synthetic DNS TXT record response.
     * The payload is base64-encoded and packed into a DNS response frame
     * for subdomain "nx.flow.internal" (internal-only, won't resolve externally).
     */
    private ByteBuf wrapAsDns(ChannelHandlerContext ctx, ByteBuf payload) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // Base64-encode the payload (DNS TXT records are ASCII-safe)
        byte[] raw = new byte[payload.readableBytes()];
        payload.getBytes(payload.readerIndex(), raw);
        byte[] b64 = Base64.getEncoder().encode(raw);

        // DNS response header (12 bytes) + question + TXT answer
        ByteBuf buf = ctx.alloc().buffer(512);

        // Transaction ID
        buf.writeShort(rng.nextInt(0, 0xFFFF));
        // Flags: QR=1(response), OPCODE=0, AA=1, TC=0, RD=1, RA=1, Z=0, RCODE=0
        buf.writeShort(0x8580);
        // QDCOUNT=1, ANCOUNT=1, NSCOUNT=0, ARCOUNT=0
        buf.writeShort(1); buf.writeShort(1); buf.writeShort(0); buf.writeShort(0);

        // Question: nx.flow.internal TXT IN
        buf.writeByte(2); buf.writeBytes("nx".getBytes());
        buf.writeByte(4); buf.writeBytes("flow".getBytes());
        buf.writeByte(8); buf.writeBytes("internal".getBytes());
        buf.writeByte(0);         // root label
        buf.writeShort(16);       // QTYPE = TXT
        buf.writeShort(1);        // QCLASS = IN

        // Answer RR: name ptr, TXT IN, TTL=60, RDATA = base64 payload
        buf.writeShort(0xC00C);   // name pointer to question
        buf.writeShort(16);       // TYPE = TXT
        buf.writeShort(1);        // CLASS = IN
        buf.writeInt(60);         // TTL = 60s
        // RDLENGTH = 1 (txt-length byte) + b64.length
        buf.writeShort(1 + b64.length);
        buf.writeByte(Math.min(b64.length, 255)); // TXT string length byte
        buf.writeBytes(b64, 0, Math.min(b64.length, 255));

        return buf;
    }

    // -----------------------------------------------------------------------
    // WebSocket framing mimicry
    // -----------------------------------------------------------------------

    /**
     * Wraps payload in a WebSocket binary data frame (RFC 6455).
     * FIN=1, RSV=0, opcode=0x2 (binary), MASK=0, payload length.
     */
    private ByteBuf wrapAsWebSocket(ChannelHandlerContext ctx, ByteBuf payload) {
        int payloadLen = payload.readableBytes();
        ByteBuf buf;

        if (payloadLen <= 125) {
            buf = ctx.alloc().buffer(2 + payloadLen);
            buf.writeByte(0x82);        // FIN=1, opcode=BINARY
            buf.writeByte(payloadLen);  // MASK=0, 7-bit length
        } else if (payloadLen <= 65535) {
            buf = ctx.alloc().buffer(4 + payloadLen);
            buf.writeByte(0x82);
            buf.writeByte(126);         // Extended 16-bit length
            buf.writeShort(payloadLen);
        } else {
            buf = ctx.alloc().buffer(10 + payloadLen);
            buf.writeByte(0x82);
            buf.writeByte(127);         // Extended 64-bit length
            buf.writeLong(payloadLen);
        }

        buf.writeBytes(payload);
        return buf;
    }

    public long getMorphedChunks() { return morphedChunks.get(); }
}
