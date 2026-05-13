package io.aetheros.chameleon;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;

import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * Inbound-only passive observer. Peeks the first inbound {@link ByteBuf} for
 * a TLS ClientHello and emits the SNI (if any) via the supplied callback,
 * then removes itself from the pipeline. Never mutates the buffer; never
 * blocks the relay.
 *
 * <p>Hard caps: examines at most one buffer, up to 16 KiB. Anything larger
 * is treated as non-TLS and the handler self-removes immediately.
 */
public final class ClientHelloPeekHandler extends ChannelDuplexHandler {

    private static final int MAX_PEEK = 16 * 1024;
    private final BiConsumer<ChannelHandlerContext, Optional<String>> onResolved;
    private boolean handled;

    public ClientHelloPeekHandler(BiConsumer<ChannelHandlerContext, Optional<String>> onResolved) {
        this.onResolved = onResolved;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!handled && msg instanceof ByteBuf buf) {
            handled = true;
            try {
                if (buf.readableBytes() <= MAX_PEEK) {
                    Optional<String> sni = ClientHelloPeek.sni(buf);
                    onResolved.accept(ctx, sni);
                } else {
                    onResolved.accept(ctx, Optional.empty());
                }
            } finally {
                ctx.pipeline().remove(this);
            }
        }
        ctx.fireChannelRead(msg);
    }
}
