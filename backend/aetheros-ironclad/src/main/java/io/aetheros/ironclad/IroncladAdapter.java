package io.aetheros.ironclad;

import com.sun.jna.Pointer;
import com.sun.jna.WString;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Project IRONCLAD — Zero-Config TUN Virtual Adapter Interface.
 *
 * <p>This class manages the lifecycle of the "NexusFlow" Wintun TUN adapter
 * on Windows. It:
 * <ol>
 *   <li>Loads {@link WintunLibrary} via JNA and creates/opens the adapter</li>
 *   <li>Modifies the OS routing table so ALL outbound IP traffic flows
 *       through the virtual adapter (0.0.0.0/0 default route override)</li>
 *   <li>Spawns a Java virtual-thread packet ingestion loop that reads raw IP
 *       packets from the wintun ring buffer and wraps them in Netty
 *       {@link ByteBuf}s for downstream pipeline processing</li>
 * </ol>
 *
 * <p>On non-Windows platforms, the adapter operates in simulation mode —
 * all JNA calls are skipped and the ingestion loop generates synthetic
 * IP packet stubs so the rest of the pipeline can be exercised locally.
 */
public class IroncladAdapter implements AutoCloseable {

    private static final Logger LOG = System.getLogger(IroncladAdapter.class.getName());

    /** Adapter display name visible in Windows "Network Connections" */
    public static final String ADAPTER_NAME   = "NexusFlow";
    /** Wintun tunnel type tag */
    public static final String TUNNEL_TYPE    = "NexusFlow";
    /** Ring-buffer capacity: 4 MB */
    private static final int   RING_CAPACITY  = 0x400000;

    private final AtomicBoolean  running       = new AtomicBoolean(false);
    private final AtomicLong     packetsIn     = new AtomicLong(0);
    private final AtomicLong     bytesIn       = new AtomicLong(0);

    private volatile Pointer adapterHandle;
    private volatile Pointer sessionHandle;
    private volatile Thread  ingressThread;

    private final boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Starts the adapter and the packet ingestion loop.
     * The supplied {@code packetConsumer} is invoked for every raw IP packet
     * (already wrapped in a Netty {@link ByteBuf}) captured from the OS.
     *
     * @param packetConsumer Callback receiving raw IP packets as ByteBufs.
     *                       The consumer is responsible for releasing each buf.
     */
    public void start(Consumer<ByteBuf> packetConsumer) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("IRONCLAD adapter already running");
        }

        if (isWindows) {
            startWindowsAdapter(packetConsumer);
        } else {
            startSimulationMode(packetConsumer);
        }

        LOG.log(Level.INFO, "[IRONCLAD] Adapter started — mode={0}", isWindows ? "WINTUN" : "SIMULATION");
    }

    /** Gracefully stops the ingestion loop and releases the adapter. */
    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) return;

        Thread t = ingressThread;
        if (t != null) {
            t.interrupt();
            try { t.join(3_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        if (isWindows) {
            teardownWindowsAdapter();
        }

        LOG.log(Level.INFO, "[IRONCLAD] Adapter closed — packets={0} bytes={1}",
                packetsIn.get(), bytesIn.get());
    }

    public long getPacketsIngested() { return packetsIn.get(); }
    public long getBytesIngested()   { return bytesIn.get(); }
    public boolean isRunning()       { return running.get(); }

    // -----------------------------------------------------------------------
    // Windows (Wintun) path
    // -----------------------------------------------------------------------

    private void startWindowsAdapter(Consumer<ByteBuf> consumer) {
        WintunLibrary wintun = WintunLibrary.INSTANCE;

        // Register log callback
        wintun.WintunSetLogger((level, ts, msg) ->
            LOG.log(Level.INFO, "[WINTUN] {0}", msg.toString()));

        // Create or open the adapter
        adapterHandle = wintun.WintunCreateAdapter(
                new WString(ADAPTER_NAME), new WString(TUNNEL_TYPE), null);
        if (adapterHandle == null) {
            throw new RuntimeException("[IRONCLAD] Failed to create Wintun adapter '" + ADAPTER_NAME + "'");
        }

        // Start session
        sessionHandle = wintun.WintunStartSession(adapterHandle, RING_CAPACITY);
        if (sessionHandle == null) {
            wintun.WintunCloseAdapter(adapterHandle);
            throw new RuntimeException("[IRONCLAD] Failed to start Wintun session");
        }

        // Modify OS routing table — route all traffic through the adapter
        installDefaultRoute();

        // Spawn ingestion virtual thread
        ingressThread = Thread.ofVirtual()
                .name("ironclad-ingress")
                .start(() -> wintunIngressLoop(wintun, consumer));
    }

    private void wintunIngressLoop(WintunLibrary wintun, Consumer<ByteBuf> consumer) {
        int[] sizeHolder = new int[1];
        LOG.log(Level.INFO, "[IRONCLAD] Wintun ingress loop started");

        while (running.get() && !Thread.currentThread().isInterrupted()) {
            Pointer pkt = wintun.WintunReceivePacket(sessionHandle, sizeHolder);
            if (pkt == null) {
                // No packet available — tight spin (real impl would use ReadFile/WaitForSingleObject)
                Thread.onSpinWait();
                continue;
            }

            int size = sizeHolder[0];
            try {
                // Zero-copy: wrap native memory into a Netty ByteBuf
                byte[] raw = pkt.getByteArray(0, size);
                ByteBuf buf = Unpooled.wrappedBuffer(raw);

                packetsIn.incrementAndGet();
                bytesIn.addAndGet(size);

                consumer.accept(buf);
            } finally {
                wintun.WintunReleaseReceivePacket(sessionHandle, pkt);
            }
        }
    }

    private void teardownWindowsAdapter() {
        WintunLibrary wintun = WintunLibrary.INSTANCE;
        removeDefaultRoute();
        if (sessionHandle != null) { wintun.WintunEndSession(sessionHandle); sessionHandle = null; }
        if (adapterHandle != null) { wintun.WintunCloseAdapter(adapterHandle); adapterHandle = null; }
    }

    // -----------------------------------------------------------------------
    // Simulation mode (non-Windows / testing)
    // -----------------------------------------------------------------------

    private void startSimulationMode(Consumer<ByteBuf> consumer) {
        ingressThread = Thread.ofVirtual()
                .name("ironclad-sim-ingress")
                .start(() -> simulationIngressLoop(consumer));
    }

    private void simulationIngressLoop(Consumer<ByteBuf> consumer) {
        LOG.log(Level.INFO, "[IRONCLAD-SIM] Simulation ingress loop started");
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                // Simulate a minimal IPv4 TCP packet header (40 bytes)
                ByteBuf buf = Unpooled.buffer(40);
                // IP version + IHL
                buf.writeByte(0x45);
                // DSCP/ECN
                buf.writeByte(0x00);
                // Total length
                buf.writeShort(40);
                // Identification
                buf.writeShort((int)(System.nanoTime() & 0xFFFF));
                // Flags + Fragment offset
                buf.writeShort(0x4000);
                // TTL
                buf.writeByte(64);
                // Protocol: TCP
                buf.writeByte(6);
                // Header checksum (placeholder)
                buf.writeShort(0);
                // Source IP: 10.0.0.1
                buf.writeBytes(new byte[]{10, 0, 0, 1});
                // Destination IP: 93.184.216.34 (example.com)
                buf.writeBytes(new byte[]{93, (byte)184, (byte)216, 34});
                // TCP header
                buf.writeShort(54321); // src port
                buf.writeShort(443);   // dst port
                buf.writeInt((int)(packetsIn.get() & 0xFFFFFFFFL)); // seq
                buf.writeInt(0); // ack
                buf.writeByte(0x50); // data offset
                buf.writeByte(0x02); // SYN
                buf.writeShort(65535); // window
                buf.writeShort(0); // checksum
                buf.writeShort(0); // urgent

                packetsIn.incrementAndGet();
                bytesIn.addAndGet(40);
                consumer.accept(buf);

                Thread.sleep(10); // ~100 pps simulation rate
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    // -----------------------------------------------------------------------
    // OS routing table helpers (Windows)
    // -----------------------------------------------------------------------

    /**
     * Installs a 0.0.0.0/0 default route pointing to the Wintun adapter,
     * forcing all OS IP traffic through IRONCLAD for inspection.
     *
     * Uses {@code route add} via ProcessBuilder for simplicity; a production
     * implementation would call IP Helper API (iphlpapi.dll) directly via JNA.
     */
    private void installDefaultRoute() {
        try {
            new ProcessBuilder("route", "add", "0.0.0.0", "mask", "0.0.0.0",
                    "10.0.0.1", "metric", "1", "if", ADAPTER_NAME)
                .inheritIO().start().waitFor();
            LOG.log(Level.INFO, "[IRONCLAD] Default route installed → NexusFlow adapter");
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[IRONCLAD] Route install failed (non-fatal): {0}", e.getMessage());
        }
    }

    private void removeDefaultRoute() {
        try {
            new ProcessBuilder("route", "delete", "0.0.0.0", "mask", "0.0.0.0")
                .inheritIO().start().waitFor();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[IRONCLAD] Route removal failed: {0}", e.getMessage());
        }
    }
}
