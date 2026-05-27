package io.aetheros.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Project DEEP-TRACE — Netty integration handler for {@link OffHeapRingBuffer}.
 *
 * <p>Intercepts the first N bytes of each new connection's inbound data
 * (connection headers, TLS ClientHello, HTTP request line, etc.) and records
 * them into the shared off-heap ring buffer for forensic analysis.
 *
 * <p>If a downstream handler later flags a threat on this connection, the
 * ring buffer slot for this connection can be instantly exposed as a structured
 * diagnostic telemetry block — giving security analysts the exact bytes that
 * preceded the alert without any disk I/O.
 *
 * <p>Only the first {@link #CAPTURE_BYTES} bytes are recorded to minimize
 * overhead. For most protocols (TLS 1.3 ClientHello, HTTP/1.1 request line,
 * SOCKS5 handshake), this captures the complete header.
 */
public class DeepTraceHandler extends ChannelInboundHandlerAdapter {

    /** Bytes to capture from the start of each connection. */
    private static final int CAPTURE_BYTES = 512;

    private final OffHeapRingBuffer ringBuffer;

    /** Tracks whether we've already captured data for this channel. */
    private final AtomicInteger capturedBytes = new AtomicInteger(0);

    private final String connectionId;

    public DeepTraceHandler(OffHeapRingBuffer ringBuffer) {
        this.ringBuffer   = ringBuffer;
        this.connectionId = UUID.randomUUID().toString().substring(0, 8);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof ByteBuf buf) {
            int already = capturedBytes.get();
            if (already < CAPTURE_BYTES) {
                int toCapture = Math.min(buf.readableBytes(), CAPTURE_BYTES - already);
                if (toCapture > 0) {
                    ByteBuf slice = buf.slice(buf.readerIndex(), toCapture);
                    ringBuffer.record(connectionId, slice);
                    capturedBytes.addAndGet(toCapture);
                }
            }
        }
        super.channelRead(ctx, msg);
    }
}
