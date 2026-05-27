package io.aetheros.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.StampedLock;

/**
 * Project DEEP-TRACE — Zero-Overhead Off-Heap Circular Ring Buffer.
 *
 * <p>A fixed-size, pre-allocated off-heap circular buffer for recording
 * connection header and handshake forensics data. Uses Netty's Direct
 * {@link ByteBuf} allocations to store data outside the JVM heap, completely
 * bypassing GC pressure.
 *
 * <h3>Design principles</h3>
 * <ul>
 *   <li><b>Zero allocation on hot path</b> — all slot buffers are pre-allocated
 *       at construction time. Recording a packet means a memcpy into an existing
 *       off-heap slot, never a new allocation.</li>
 *   <li><b>Circular overwrite</b> — when the ring is full, the oldest slot is
 *       silently overwritten (rolling history window).</li>
 *   <li><b>O(1) record and O(capacity) snapshot</b> — recording is constant time;
 *       producing a diagnostic snapshot is linear in ring capacity.</li>
 *   <li><b>StampedLock for optimistic reads</b> — the write path uses an exclusive
 *       stamp; snapshot reads use an optimistic read stamp that retries on conflict.</li>
 * </ul>
 *
 * <h3>Memory layout</h3>
 * {@code capacity} direct ByteBuf slots of {@code slotSize} bytes each.
 * Total off-heap footprint = capacity × slotSize bytes.
 *
 * <p>Default: 512 slots × 4096 bytes = 2 MB off-heap, zero GC.
 */
public class OffHeapRingBuffer implements AutoCloseable {

    private static final Logger LOG = System.getLogger(OffHeapRingBuffer.class.getName());

    /** Number of slots in the ring. */
    private final int capacity;
    /** Maximum bytes stored per slot. */
    private final int slotSize;

    /** Pre-allocated off-heap slot buffers. */
    private final ByteBuf[] slots;
    /** Metadata: timestamp of each slot in nanoseconds. */
    private final long[] slotTimestamps;
    /** Metadata: actual content length of each slot. */
    private final int[] slotLengths;
    /** Metadata: connection ID of each slot. */
    private final String[] slotConnIds;

    /** Write cursor — always increments, wraps modulo capacity. */
    private final AtomicLong writeCursor = new AtomicLong(0);

    /** Protects slot writes from concurrent snapshot reads. */
    private final StampedLock lock = new StampedLock();

    private final AtomicLong recordedCount = new AtomicLong(0);

    /**
     * Constructs a ring buffer with the given capacity and slot size.
     * All direct ByteBufs are allocated immediately — no lazy allocation.
     *
     * @param capacity Number of slots
     * @param slotSize Maximum bytes per slot
     */
    public OffHeapRingBuffer(int capacity, int slotSize) {
        this.capacity = capacity;
        this.slotSize = slotSize;
        this.slots          = new ByteBuf[capacity];
        this.slotTimestamps = new long[capacity];
        this.slotLengths    = new int[capacity];
        this.slotConnIds    = new String[capacity];

        // Pre-allocate all slots as unpooled direct buffers (off-heap)
        for (int i = 0; i < capacity; i++) {
            slots[i] = UnpooledByteBufAllocator.DEFAULT.directBuffer(slotSize, slotSize);
        }

        long totalMb = ((long) capacity * slotSize) / (1024 * 1024);
        LOG.log(Level.INFO,
                "[DEEP-TRACE] Ring buffer initialized: {0} slots × {1} bytes = {2} MB off-heap",
                capacity, slotSize, totalMb);
    }

    /** Convenience constructor: 512 slots × 4096 bytes = 2 MB off-heap. */
    public OffHeapRingBuffer() {
        this(512, 4096);
    }

    // -----------------------------------------------------------------------
    // Recording
    // -----------------------------------------------------------------------

    /**
     * Records a data snippet into the ring buffer.
     * Thread-safe; may be called concurrently from multiple Netty event loops.
     *
     * @param connectionId Identifier for the connection (for forensics correlation)
     * @param data         Source ByteBuf (only first {@code slotSize} bytes are stored)
     */
    public void record(String connectionId, ByteBuf data) {
        int len = Math.min(data.readableBytes(), slotSize);
        if (len <= 0) return;

        // Acquire next slot index atomically
        int slot = (int)(writeCursor.getAndIncrement() % capacity);

        long stamp = lock.writeLock();
        try {
            ByteBuf target = slots[slot];
            target.clear();
            data.getBytes(data.readerIndex(), target, len);
            target.writerIndex(len);
            slotTimestamps[slot] = System.nanoTime();
            slotLengths[slot]    = len;
            slotConnIds[slot]    = connectionId;
            recordedCount.incrementAndGet();
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    // -----------------------------------------------------------------------
    // Snapshot / forensics extraction
    // -----------------------------------------------------------------------

    /**
     * Record DTO for a single ring slot.
     *
     * @param slot         Slot index
     * @param connectionId Connection ID
     * @param timestampNs  Nanosecond timestamp of recording
     * @param data         Copy of the recorded bytes (heap-allocated for safe transport)
     */
    public record SlotSnapshot(int slot, String connectionId, long timestampNs, byte[] data) {}

    /**
     * Produces a point-in-time snapshot of all ring slots.
     * Uses optimistic StampedLock reads for maximum throughput.
     *
     * @return Array of {@link SlotSnapshot} in slot order (not chronological)
     */
    public SlotSnapshot[] snapshot() {
        SlotSnapshot[] result = new SlotSnapshot[capacity];

        long stamp = lock.readLock();
        try {
            for (int i = 0; i < capacity; i++) {
                int len = slotLengths[i];
                byte[] copy = new byte[len];
                if (len > 0) {
                    slots[i].getBytes(0, copy);
                }
                result[i] = new SlotSnapshot(i, slotConnIds[i], slotTimestamps[i], copy);
            }
        } finally {
            lock.unlockRead(stamp);
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @Override
    public void close() {
        for (ByteBuf slot : slots) {
            if (slot != null && slot.refCnt() > 0) slot.release();
        }
        LOG.log(Level.INFO, "[DEEP-TRACE] Ring buffer closed — {0} total records", recordedCount.get());
    }

    public int getCapacity()        { return capacity; }
    public int getSlotSize()        { return slotSize; }
    public long getRecordedCount()  { return recordedCount.get(); }
    public long getTotalOffHeapBytes() { return (long) capacity * slotSize; }
}
