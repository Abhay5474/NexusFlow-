package io.aetheros.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Project GHOST-SHARD — Anti-DPI Byte-Level Fragmentation Encoder.
 *
 * <p>A Netty {@link MessageToMessageEncoder} that defeats Deep Packet Inspection
 * systems by transforming outbound TCP payload streams in two ways:
 *
 * <ol>
 *   <li><b>Randomized fragmentation</b> — each incoming {@link ByteBuf} is
 *       sliced into a random number of sub-chunks with randomized byte boundaries.
 *       DPI engines that rely on fixed-offset signature matching (e.g., checking
 *       bytes 0–3 of a TLS ClientHello) cannot function when the TLS handshake
 *       is distributed across multiple TCP segments of varying sizes.</li>
 *   <li><b>Cryptographic noise padding</b> — random-length noise payloads are
 *       interleaved between genuine payload chunks. These are marked with a
 *       custom framing header (0xDEAD magic + length) so the receiving side
 *       (NexusFlow peer) can strip them. Against passive DPI taps that don't
 *       understand the framing, the stream appears as unclassifiable binary.</li>
 * </ol>
 *
 * <h3>Fragment size distribution</h3>
 * Chunk sizes are drawn from a bimodal distribution to avoid detectable
 * uniform-random patterns:
 * <ul>
 *   <li>50% chance: small chunk (12–127 bytes)</li>
 *   <li>40% chance: medium chunk (128–847 bytes)</li>
 *   <li>10% chance: full remaining payload</li>
 * </ul>
 *
 * <p>This handler must be added to the pipeline <em>before</em> the TCP
 * transport encoder so fragmentation happens at the application layer.
 */
public class GhostShardEncoder extends MessageToMessageEncoder<ByteBuf> {

    private static final Logger LOG = System.getLogger(GhostShardEncoder.class.getName());

    /** Noise frame magic number — 2 bytes. */
    private static final short NOISE_MAGIC = (short) 0xDEAD;

    /** Minimum noise payload length (bytes). */
    private static final int NOISE_MIN = 4;
    /** Maximum noise payload length (bytes). */
    private static final int NOISE_MAX = 64;

    /** Minimum fragment size. */
    private static final int FRAG_MIN_SMALL  = 12;
    private static final int FRAG_MAX_SMALL  = 127;
    private static final int FRAG_MIN_MEDIUM = 128;
    private static final int FRAG_MAX_MEDIUM = 847;

    /** Secure RNG for noise generation. */
    private static final SecureRandom SRNG = new SecureRandom();

    private final AtomicLong morphedPayloads = new AtomicLong(0);
    private final AtomicLong shardsEmitted   = new AtomicLong(0);

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out)
            throws Exception {
        if (!msg.isReadable()) return;

        morphedPayloads.incrementAndGet();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        int remaining = msg.readableBytes();

        while (remaining > 0) {
            // Determine fragment size
            int chunkSize = pickChunkSize(rng, remaining);
            chunkSize = Math.min(chunkSize, remaining);

            // Emit the genuine payload shard
            ByteBuf shard = ctx.alloc().buffer(chunkSize);
            msg.readBytes(shard, chunkSize);
            out.add(shard);
            shardsEmitted.incrementAndGet();
            remaining -= chunkSize;

            // Interleave a noise frame after every shard (except the last)
            if (remaining > 0 && rng.nextBoolean()) {
                out.add(buildNoisePadding(ctx, rng));
            }
        }

        LOG.log(Level.TRACE, "[GHOST-SHARD] Encoded payload → {0} shards", shardsEmitted.get());
    }

    private int pickChunkSize(ThreadLocalRandom rng, int remaining) {
        int roll = rng.nextInt(100);
        if (roll < 50) {
            // Small chunk
            return rng.nextInt(FRAG_MIN_SMALL, Math.min(FRAG_MAX_SMALL + 1, remaining + 1));
        } else if (roll < 90) {
            // Medium chunk
            return rng.nextInt(FRAG_MIN_MEDIUM, Math.min(FRAG_MAX_MEDIUM + 1, remaining + 1));
        } else {
            // Remaining (full flush)
            return remaining;
        }
    }

    private ByteBuf buildNoisePadding(ChannelHandlerContext ctx, ThreadLocalRandom rng) {
        int noiseLen = rng.nextInt(NOISE_MIN, NOISE_MAX + 1);
        // Frame: [0xDEAD (2)] [length (2)] [random bytes (noiseLen)]
        ByteBuf noise = ctx.alloc().buffer(4 + noiseLen);
        noise.writeShort(NOISE_MAGIC);
        noise.writeShort(noiseLen);
        byte[] noisebytes = new byte[noiseLen];
        SRNG.nextBytes(noisebytes);
        noise.writeBytes(noisebytes);
        return noise;
    }

    public long getMorphedPayloads() { return morphedPayloads.get(); }
    public long getShardsEmitted()   { return shardsEmitted.get(); }
}
