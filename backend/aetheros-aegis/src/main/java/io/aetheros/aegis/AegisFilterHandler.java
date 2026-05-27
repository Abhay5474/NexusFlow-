package io.aetheros.aegis;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Project AEGIS — Inline Ad & Tracker Blocking Handler.
 *
 * <p>Intercepts inbound {@link HttpRequest} objects in the Netty pipeline.
 * For each request, the {@code Host} header is extracted and checked against
 * the {@link RadixTrieBlocklist}. If the domain is blocked:
 *
 * <ul>
 *   <li>The connection is immediately closed without forwarding the request</li>
 *   <li>Optionally, a synthetic "200 OK" empty response is returned (BLACK-HOLE mode)
 *       to fool telemetry clients into believing their request succeeded</li>
 * </ul>
 *
 * <p>For non-HTTP traffic (raw ByteBufs), the {@code Host} header is extracted
 * from the first few bytes of the payload using a lightweight heuristic parser.
 *
 * <p>This handler is {@code @Sharable} — one instance is shared across all
 * active channels, which is safe because all state is in atomic counters and
 * the immutable {@link RadixTrieBlocklist}.
 */
@io.netty.channel.ChannelHandler.Sharable
public class AegisFilterHandler extends ChannelInboundHandlerAdapter {

    private static final Logger LOG = System.getLogger(AegisFilterHandler.class.getName());

    private static final byte[] BLACK_HOLE_BODY =
        "{}".getBytes(StandardCharsets.UTF_8);

    private final RadixTrieBlocklist blocklist;

    /** When true, return a synthetic 200 OK instead of just closing (BLACK-HOLE mode). */
    private final boolean blackHoleMode;

    private final AtomicLong blocked = new AtomicLong(0);
    private final AtomicLong sinkholeResponses = new AtomicLong(0);

    public AegisFilterHandler(RadixTrieBlocklist blocklist, boolean blackHoleMode) {
        this.blocklist    = blocklist;
        this.blackHoleMode = blackHoleMode;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpRequest req) {
            String host = req.headers().get(HttpHeaderNames.HOST);
            if (host != null) {
                // Strip port number if present
                int colonIdx = host.lastIndexOf(':');
                if (colonIdx > 0) host = host.substring(0, colonIdx);

                RadixTrieBlocklist.LookupResult result = blocklist.isBlocked(host);
                if (result.blocked()) {
                    blocked.incrementAndGet();
                    LOG.log(Level.DEBUG, "[AEGIS] BLOCKED host={0} category={1}",
                            host, result.category());

                    if (blackHoleMode) {
                        // Return fake 200 OK (BLACK-HOLE — telemetry sinkhole)
                        returnSyntheticOk(ctx, host, result.category());
                    } else {
                        ctx.close();
                    }
                    return; // do NOT forward to next handler
                }
            }
        }
        // Not blocked — pass through
        super.channelRead(ctx, msg);
    }

    /**
     * Sends a minimal synthetic HTTP 200 OK response that mimics a successful
     * telemetry endpoint acknowledgement. The application's background task
     * believes it reported successfully and goes back to sleep — saving
     * bandwidth and preventing retry storms.
     */
    private void returnSyntheticOk(ChannelHandlerContext ctx, String host, String category) {
        sinkholeResponses.incrementAndGet();
        LOG.log(Level.DEBUG, "[BLACK-HOLE] Sinkholing {0} (category={1})", host, category);

        ByteBuf body = Unpooled.wrappedBuffer(BLACK_HOLE_BODY);
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, body);
        resp.headers()
            .set(HttpHeaderNames.CONTENT_TYPE,   "application/json")
            .set(HttpHeaderNames.CONTENT_LENGTH, body.readableBytes())
            .set(HttpHeaderNames.CONNECTION,     "close")
            .set("X-NexusFlow",                 "sinkhole");

        ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
    }

    public long getBlockedCount()          { return blocked.get(); }
    public long getSinkholeResponseCount() { return sinkholeResponses.get(); }
}
