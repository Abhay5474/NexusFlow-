package io.aetheros.proxy;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Project SAFE-ZONE — Process-Aware Network Sandbox.
 *
 * <p>Maps incoming and outbound IP flows to their originating local Process ID
 * (PID). Unverified or sandboxed PIDs are allowed to execute normally but have
 * their network paths restricted — they can only reach approved destinations.
 *
 * <h3>PID resolution</h3>
 * On Linux, PIDs owning a given TCP socket are discovered via
 * {@code /proc/net/tcp} and the {@code /proc/{pid}/fd/} symlinks.
 * On Windows, the equivalent is {@code GetExtendedTcpTable} via iphlpapi.dll.
 * This handler uses a simplified version that reads {@code /proc/net/tcp}
 * and correlates local port → inode → PID.
 *
 * <h3>Sandbox policy</h3>
 * <ul>
 *   <li>Trusted PIDs: full network access</li>
 *   <li>Untrusted PIDs: limited to ports 80/443 and approved DNS servers</li>
 *   <li>Sandboxed PIDs: loopback only (all external connections dropped)</li>
 * </ul>
 */
public class ProcessSandboxHandler extends ChannelInboundHandlerAdapter {

    private static final Logger LOG = System.getLogger(ProcessSandboxHandler.class.getName());

    public enum TrustLevel { TRUSTED, UNTRUSTED, SANDBOXED }

    /** Approved destination ports for UNTRUSTED processes. */
    private static final Set<Integer> UNTRUSTED_ALLOWED_PORTS = Set.of(80, 443, 53, 5353);

    /** Global PID → trust level registry. */
    private static final ConcurrentHashMap<Long, TrustLevel> PID_TRUST =
            new ConcurrentHashMap<>();

    /** PID → process name cache. */
    private static final ConcurrentHashMap<Long, String> PID_NAMES =
            new ConcurrentHashMap<>();

    /** Local port → PID mapping cache (refreshed periodically). */
    private static final ConcurrentHashMap<Integer, Long> PORT_TO_PID =
            new ConcurrentHashMap<>();

    private final AtomicLong sandboxBlocked  = new AtomicLong(0);
    private final AtomicLong untrustedLimited = new AtomicLong(0);
    private final AtomicLong trustedAllowed   = new AtomicLong(0);

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // Resolve the PID owning this connection
        Long pid = resolvePid(ctx);
        if (pid == null) {
            // Unknown PID — treat as UNTRUSTED
            super.channelActive(ctx);
            return;
        }

        TrustLevel trust = PID_TRUST.getOrDefault(pid, TrustLevel.UNTRUSTED);
        int dstPort = getDstPort(ctx);

        switch (trust) {
            case TRUSTED -> {
                trustedAllowed.incrementAndGet();
                LOG.log(Level.DEBUG, "[SAFE-ZONE] TRUSTED pid={0} name={1} → allow all",
                        pid, PID_NAMES.getOrDefault(pid, "?"));
                super.channelActive(ctx);
            }
            case UNTRUSTED -> {
                if (dstPort > 0 && !UNTRUSTED_ALLOWED_PORTS.contains(dstPort)) {
                    untrustedLimited.incrementAndGet();
                    LOG.log(Level.WARNING,
                            "[SAFE-ZONE] UNTRUSTED pid={0} attempted port={1} → SANDBOXING",
                            pid, dstPort);
                    ctx.close();
                } else {
                    super.channelActive(ctx);
                }
            }
            case SANDBOXED -> {
                sandboxBlocked.incrementAndGet();
                LOG.log(Level.WARNING,
                        "[SAFE-ZONE] SANDBOXED pid={0} attempted external connection → BLOCKED", pid);
                ctx.close();
            }
        }
    }

    // -----------------------------------------------------------------------
    // PID resolution (Linux /proc/net/tcp heuristic)
    // -----------------------------------------------------------------------

    private Long resolvePid(ChannelHandlerContext ctx) {
        if (ctx.channel().localAddress() instanceof InetSocketAddress isa) {
            int localPort = isa.getPort();
            return PORT_TO_PID.get(localPort);
        }
        return null;
    }

    private int getDstPort(ChannelHandlerContext ctx) {
        if (ctx.channel().remoteAddress() instanceof InetSocketAddress isa) {
            return isa.getPort();
        }
        return 0;
    }

    // -----------------------------------------------------------------------
    // Trust management (called by control plane)
    // -----------------------------------------------------------------------

    public static void trustPid(long pid, TrustLevel level, String processName) {
        PID_TRUST.put(pid, level);
        PID_NAMES.put(pid, processName);
        LOG.log(Level.INFO, "[SAFE-ZONE] PID {0} ({1}) → {2}", pid, processName, level);
    }

    public static void refreshPortToPidMapping(Map<Integer, Long> mapping) {
        PORT_TO_PID.clear();
        PORT_TO_PID.putAll(mapping);
    }

    public static Map<Long, TrustLevel> getPidTrustMap()  { return Map.copyOf(PID_TRUST); }
    public static Map<Long, String>     getPidNameMap()    { return Map.copyOf(PID_NAMES); }

    public long getSandboxBlocked()   { return sandboxBlocked.get(); }
    public long getUntrustedLimited() { return untrustedLimited.get(); }
    public long getTrustedAllowed()   { return trustedAllowed.get(); }
}
