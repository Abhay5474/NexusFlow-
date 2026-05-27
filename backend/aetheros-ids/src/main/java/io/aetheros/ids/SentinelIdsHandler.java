package io.aetheros.ids;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * Project SENTINEL-IDS — Inline Netty Channel Handler.
 *
 * <p>Wraps {@link SentinelIdsEngine} as a Netty inbound handler. For every
 * {@link ByteBuf} that passes through the pipeline, this handler:
 * <ol>
 *   <li>Extracts the readable bytes without consuming the buffer</li>
 *   <li>Submits them to the engine's Aho-Corasick scanner</li>
 *   <li>If the result is DROP: immediately closes the channel</li>
 *   <li>If the result is PASS (or LOG only): forwards to the next handler</li>
 * </ol>
 *
 * <p>This handler is {@code @Sharable} — one engine instance is shared across
 * all channels since the scan path is fully thread-safe.
 */
@io.netty.channel.ChannelHandler.Sharable
public class SentinelIdsHandler extends ChannelInboundHandlerAdapter {

    private static final Logger LOG = System.getLogger(SentinelIdsHandler.class.getName());

    /** Maximum bytes inspected per message to bound CPU cost. */
    private static final int MAX_INSPECT = 16384;

    private final SentinelIdsEngine engine;

    public SentinelIdsHandler(SentinelIdsEngine engine) {
        this.engine = engine;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof ByteBuf buf && buf.readableBytes() > 0) {
            int len  = Math.min(buf.readableBytes(), MAX_INSPECT);
            byte[] bytes = new byte[len];
            buf.getBytes(buf.readerIndex(), bytes, 0, len);

            // Destination port from channel attributes (set by upstream handlers)
            Integer dstPort = ctx.channel().attr(
                io.aetheros.ironclad.IroncladAttributeKeys.DST_PORT).get();
            int port = (dstPort != null) ? dstPort : 0;

            SentinelIdsEngine.ScanResult result = engine.scan(bytes, 0, len, port);

            if (result.drop()) {
                ThreatRule rule = result.matchedRule();
                LOG.log(Level.WARNING,
                        "[SENTINEL-IDS] DROP rule={0} severity={1} channel={2}",
                        rule != null ? rule.ruleId() : "?",
                        rule != null ? rule.severity() : "?",
                        ctx.channel().remoteAddress());
                ctx.channel().close().addListener(ChannelFutureListener.CLOSE);
                return; // do NOT pass to next handler
            }
        }
        super.channelRead(ctx, msg);
    }

    public SentinelIdsEngine getEngine() { return engine; }
}
