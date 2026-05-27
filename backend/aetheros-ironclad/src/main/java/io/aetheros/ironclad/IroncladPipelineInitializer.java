package io.aetheros.ironclad;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;

/**
 * Netty {@link ChannelInitializer} for the IRONCLAD ingestion pipeline.
 *
 * Pipeline stages:
 * <pre>
 *   [Wintun Ring Buffer]
 *        ↓
 *   RawIpPacketHandler   — parse IP/TCP/UDP headers, attach channel attributes
 *        ↓
 *   [downstream handlers — GHOST-SHARD, SENTINEL-IDS, AEGIS, etc.]
 * </pre>
 */
public class IroncladPipelineInitializer extends ChannelInitializer<SocketChannel> {

    private final RawIpPacketHandler rawIpHandler;

    public IroncladPipelineInitializer() {
        this.rawIpHandler = new RawIpPacketHandler();
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline p = ch.pipeline();
        p.addLast("raw-ip-parser", rawIpHandler);
        // Additional handlers (GHOST-SHARD, SENTINEL-IDS, AEGIS filter, etc.)
        // are added by the control plane configuration after initialization.
    }

    public RawIpPacketHandler getRawIpHandler() {
        return rawIpHandler;
    }
}
