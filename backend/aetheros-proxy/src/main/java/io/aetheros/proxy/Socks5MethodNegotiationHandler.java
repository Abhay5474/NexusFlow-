package io.aetheros.proxy;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.socksx.v5.Socks5AuthMethod;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequest;
import io.netty.handler.codec.socksx.v5.DefaultSocks5InitialResponse;

/**
 * SOCKS5 Phase 1: method negotiation (RFC 1928).
 *
 * <pre>
 *   Client → Server:  +----+----------+----------+
 *                     |VER | NMETHODS | METHODS  |
 *                     +----+----------+----------+
 *   Server → Client:  +----+--------+
 *                     |VER | METHOD |
 *                     +----+--------+
 * </pre>
 *
 * For now: no-auth only. After replying, we swap our decoder for the
 * request decoder and remove ourselves from the pipeline.
 */
public final class Socks5MethodNegotiationHandler extends SimpleChannelInboundHandler<Socks5InitialRequest> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Socks5InitialRequest msg) {
        if (!msg.authMethods().contains(Socks5AuthMethod.NO_AUTH)) {
            ctx.writeAndFlush(new DefaultSocks5InitialResponse(Socks5AuthMethod.UNACCEPTED))
               .addListener(f -> ctx.close());
            return;
        }
        ctx.writeAndFlush(new DefaultSocks5InitialResponse(Socks5AuthMethod.NO_AUTH));

        ctx.pipeline()
           .replace("phase1-decode", "phase3-decode", new Socks5CommandRequestDecoder());
        ctx.pipeline().remove(this);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }
}
