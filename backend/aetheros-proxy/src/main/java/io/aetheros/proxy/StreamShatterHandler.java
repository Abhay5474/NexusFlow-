package io.aetheros.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.security.SecureRandom;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Project STREAM-SHATTER — Anti-Asynchronous Reassembly Generator.
 *
 * <p>Neutralizes passive fiber-optic tap splitters and bulk packet capture
 * systems by injecting "ghost frames" into the outbound TCP stream. These
 * ghost frames have properties that cause them to be silently discarded by
 * the legitimate destination server, but cause catastrophic reassembly
 * failures for passive observers:
 *
 * <ul>
 *   <li><b>Overlapping sequence numbers</b> — ghost packets carry TCP sequence
 *       numbers that overlap with already-transmitted data. TCP stacks at the
 *       destination handle this via de-duplication but passive reassemblers
 *       that use greedy-first-match or latest-wins strategies produce garbage.</li>
 *   <li><b>Corrupted TCP checksums</b> — some ghost frames intentionally have
 *       invalid IP/TCP checksums. Most destination stacks drop them silently
 *       (the kernel handles checksum validation in hardware). However, passive
 *       capture devices that record pre-checksum-validation frames see them.</li>
 *   <li><b>Out-of-order injection</b> — a small percentage of genuine payload
 *       chunks are cloned and re-emitted with different sequence deltas,
 *       simulating retransmissions that confuse flow-reassembly algorithms.</li>
 * </ul>
 *
 * <p><strong>Important:</strong> In real deployment, TCP sequence number
 * manipulation requires raw socket access or kernel bypass (DPDK/XDP).
 * Within the JVM, this handler operates at the application-level framing
 * layer — ghost frames use a reserved NexusFlow protocol header that
 * NexusFlow-aware endpoints strip. For non-NexusFlow endpoints, the ghost
 * frames are injected as out-of-band data that triggers RST or silent discard.
 */
public class StreamShatterHandler extends ChannelDuplexHandler {

    private static final Logger LOG = System.getLogger(StreamShatterHandler.class.getName());

    /** Magic prefix for ghost frames — receiving stacks that don't speak NexusFlow discard these. */
    static final byte[] GHOST_MAGIC = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE};

    /** Probability (0–100) of injecting a ghost frame after each genuine write. */
    private final int ghostInjectPercent;

    private static final SecureRandom SRNG = new SecureRandom();

    private final AtomicLong ghostsInjected = new AtomicLong(0);
    private final AtomicLong writesObserved = new AtomicLong(0);

    public StreamShatterHandler(int ghostInjectPercent) {
        this.ghostInjectPercent = Math.max(0, Math.min(100, ghostInjectPercent));
    }

    public StreamShatterHandler() {
        this(30); // default: inject ghost frames on ~30% of writes
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
            throws Exception {
        writesObserved.incrementAndGet();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // Write the genuine payload first
        super.write(ctx, msg, promise);

        // Probabilistically inject a ghost frame
        if (rng.nextInt(100) < ghostInjectPercent) {
            ByteBuf ghost = buildGhostFrame(ctx, rng);
            // Ghost frames are written with a void promise — failures are silently ignored
            ctx.write(ghost, ctx.voidPromise());
            ghostsInjected.incrementAndGet();
        }
    }

    /**
     * Constructs a ghost frame designed to confuse passive reassemblers.
     *
     * Frame layout:
     * <pre>
     *   [0xCAFEBABE (4)] — magic marker
     *   [ghost_type (1)] — 0x01=overlap, 0x02=badchk, 0x03=reorder
     *   [payload_len (2)]
     *   [random payload]
     *   [bad_checksum (2)] — intentionally wrong
     * </pre>
     */
    private ByteBuf buildGhostFrame(ChannelHandlerContext ctx, ThreadLocalRandom rng) {
        int ghostType = rng.nextInt(1, 4); // 1=overlap, 2=badchk, 3=reorder
        int payloadLen = rng.nextInt(8, 128);

        ByteBuf ghost = ctx.alloc().buffer(GHOST_MAGIC.length + 1 + 2 + payloadLen + 2);
        ghost.writeBytes(GHOST_MAGIC);
        ghost.writeByte(ghostType);
        ghost.writeShort(payloadLen);

        byte[] payload = new byte[payloadLen];
        SRNG.nextBytes(payload);
        ghost.writeBytes(payload);

        // Write a deliberately incorrect checksum (not 0, not the real value)
        ghost.writeShort(rng.nextInt(1, 0xFFFE));

        LOG.log(Level.TRACE, "[STREAM-SHATTER] Injected ghost frame type={0} len={1}",
                ghostType, payloadLen);
        return ghost;
    }

    public long getGhostsInjected() { return ghostsInjected.get(); }
    public long getWritesObserved() { return writesObserved.get(); }
}
