package io.aetheros.ironclad;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Guid;

/**
 * JNA binding for wintun.dll — Wireguard's kernel-mode TUN driver.
 *
 * This interface maps the core wintun C API into Java, allowing the JVM to
 * create and manage virtual TUN network adapters at the OS level without
 * any native C glue code.
 *
 * On non-Windows platforms, this binding is silently stubbed by
 * {@link IroncladAdapter} to permit cross-platform compilation.
 *
 * Reference: https://git.zx2c4.com/wintun/about/
 */
public interface WintunLibrary extends Library {

    /**
     * Singleton instance — loaded lazily by {@link IroncladAdapter}.
     * We do NOT call Native.load() at class init time so non-Windows JVMs
     * can still load the class without throwing UnsatisfiedLinkError.
     */
    WintunLibrary INSTANCE = Native.load("wintun", WintunLibrary.class);

    // -----------------------------------------------------------------------
    // Adapter lifecycle
    // -----------------------------------------------------------------------

    /**
     * Creates or opens a Wintun adapter.
     *
     * @param name          Adapter name shown in Windows network settings (e.g. "NexusFlow")
     * @param tunnelType    Tunnel type string (e.g. "NexusFlow")
     * @param requestedGuid Optional GUID; pass null to auto-generate
     * @return Opaque adapter handle, or null on failure
     */
    Pointer WintunCreateAdapter(WString name, WString tunnelType, Guid.GUID requestedGuid);

    /**
     * Closes and destroys a Wintun adapter handle.
     *
     * @param adapter Handle returned by {@link #WintunCreateAdapter}
     */
    void WintunCloseAdapter(Pointer adapter);

    // -----------------------------------------------------------------------
    // Session management
    // -----------------------------------------------------------------------

    /**
     * Starts a Wintun session on the adapter.
     *
     * @param adapter     Adapter handle
     * @param capacity    Ring buffer capacity in bytes (must be power of 2, 0x20000 – 0x4000000)
     * @return Opaque session handle, or null on failure
     */
    Pointer WintunStartSession(Pointer adapter, int capacity);

    /**
     * Ends a Wintun session.
     *
     * @param session Session handle
     */
    void WintunEndSession(Pointer session);

    // -----------------------------------------------------------------------
    // Packet receive (ingress from OS)
    // -----------------------------------------------------------------------

    /**
     * Receives the next available packet from the OS kernel.
     * Returns null if no packet is currently available (non-blocking).
     *
     * @param session     Session handle
     * @param packetSize  Output: receives the size of the packet in bytes
     * @return Pointer to packet data in the wintun ring buffer, or null
     */
    Pointer WintunReceivePacket(Pointer session, int[] packetSize);

    /**
     * Releases a received packet back to the ring buffer.
     * Must be called after processing a packet from {@link #WintunReceivePacket}.
     *
     * @param session Session handle
     * @param packet  Packet pointer returned by WintunReceivePacket
     */
    void WintunReleaseReceivePacket(Pointer session, Pointer packet);

    // -----------------------------------------------------------------------
    // Packet send (egress back to OS)
    // -----------------------------------------------------------------------

    /**
     * Allocates a send packet buffer in the wintun ring.
     *
     * @param session    Session handle
     * @param packetSize Size of the packet to send
     * @return Pointer to the allocated send buffer, or null if ring is full
     */
    Pointer WintunAllocateSendPacket(Pointer session, int packetSize);

    /**
     * Sends a packet that was previously allocated via {@link #WintunAllocateSendPacket}.
     *
     * @param session Session handle
     * @param packet  Packet buffer allocated by WintunAllocateSendPacket
     */
    void WintunSendPacket(Pointer session, Pointer packet);

    // -----------------------------------------------------------------------
    // Logging
    // -----------------------------------------------------------------------

    /**
     * Functional interface for the native wintun logger callback.
     */
    interface WintunLogger extends com.sun.jna.Callback {
        void log(int level, long timestamp, WString message);
    }

    /**
     * Registers a Java callback that receives log messages from the wintun driver.
     *
     * @param newLogger Logger implementation, or null to disable
     */
    void WintunSetLogger(WintunLogger newLogger);
}
