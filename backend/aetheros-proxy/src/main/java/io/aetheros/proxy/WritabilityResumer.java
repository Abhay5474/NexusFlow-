package io.aetheros.proxy;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * Sits on the peer channel; when *this* channel becomes writable again,
 * resume reads on the partner. Closes the loop in
 * {@link RelayHandler}'s backpressure scheme.
 */
public final class WritabilityResumer extends ChannelInboundHandlerAdapter {

    private final Channel partner;

    public WritabilityResumer(Channel partner) {
        this.partner = partner;
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        if (ctx.channel().isWritable() && partner.isActive()) {
            partner.read();
        }
        ctx.fireChannelWritabilityChanged();
    }
}
