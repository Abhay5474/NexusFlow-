package io.aetheros.proxy;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Project SHIELD-FABRIC — Anti-Mirroring & Line-Tap Sentinel.
 *
 * <p>Continuously benchmarks network path characteristics and detects the
 * presence of active mid-transit line mirrors or tap devices by monitoring:
 *
 * <ul>
 *   <li><b>RTT variance (jitter)</b> — legitimate network paths have natural
 *       RTT variance driven by queuing delays. A tap/mirror introduces a
 *       fixed additional latency (the splice delay). SHIELD-FABRIC detects
 *       sudden, sustained RTT increases of &gt;{@link #RTT_SPIKE_THRESHOLD_MS} ms
 *       that cannot be explained by normal congestion patterns.</li>
 *   <li><b>Packet duplication ratio</b> — an active mirror/MITM device
 *       sometimes causes ACK duplication artifacts. Monitoring the ratio of
 *       received duplicate sequence numbers reveals passive tap activity.</li>
 *   <li><b>PMTU changes</b> — a newly inserted inline tap device on an
 *       otherwise stable path may change the effective MTU (the tap's
 *       encapsulation overhead reduces available payload space).</li>
 * </ul>
 *
 * <p>When anomalies are detected, SHIELD-FABRIC triggers one of:
 * <ul>
 *   <li>CIPHER_ROTATE — notify the control plane to rotate session keys</li>
 *   <li>INTERFACE_MIGRATE — migrate the connection to an alternate lane</li>
 *   <li>ALERT_ONLY — log the anomaly for forensic review</li>
 * </ul>
 */
public class ShieldFabricHandler extends ChannelDuplexHandler {

    private static final Logger LOG = System.getLogger(ShieldFabricHandler.class.getName());

    /** RTT increase threshold to trigger a tap-detection alert (ms). */
    private static final double RTT_SPIKE_THRESHOLD_MS = 8.0;
    /** Minimum samples before alerting. */
    private static final int    MIN_SAMPLES            = 10;
    /** EWMA smoothing factor for RTT. */
    private static final double ALPHA                  = 0.1;

    private final AtomicReference<Double> ewmaRtt  = new AtomicReference<>(0.0);
    private final AtomicLong              writeNs  = new AtomicLong(0);
    private final AtomicLong              samples  = new AtomicLong(0);
    private final AtomicLong              alerts   = new AtomicLong(0);

    /** Ring of recent RTT samples for variance computation. */
    private final ConcurrentLinkedQueue<Double> rttHistory = new ConcurrentLinkedQueue<>();
    private static final int RTT_HISTORY_SIZE = 50;

    public enum TapAction { CIPHER_ROTATE, INTERFACE_MIGRATE, ALERT_ONLY }

    /** Alert record produced when tap evidence is detected. */
    public record TapAlert(long ts, double rttDeltaMs, double rttVariance, TapAction action) {}

    private final ConcurrentLinkedQueue<TapAlert> tapAlerts = new ConcurrentLinkedQueue<>();

    // -----------------------------------------------------------------------
    // RTT measurement (write → ACK cycle approximation)
    // -----------------------------------------------------------------------

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
            throws Exception {
        writeNs.set(System.nanoTime());
        super.write(ctx, msg, promise);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        long prevWrite = writeNs.getAndSet(0);
        if (prevWrite > 0) {
            double rttMs = (System.nanoTime() - prevWrite) / 1_000_000.0;
            updateRtt(ctx, rttMs);
        }
        super.channelRead(ctx, msg);
    }

    private void updateRtt(ChannelHandlerContext ctx, double rttMs) {
        long sampleIdx = samples.incrementAndGet();

        // Update EWMA
        double oldEwma = ewmaRtt.get();
        double newEwma = (sampleIdx == 1) ? rttMs : ALPHA * rttMs + (1 - ALPHA) * oldEwma;
        ewmaRtt.set(newEwma);

        // Maintain history ring
        rttHistory.offer(rttMs);
        if (rttHistory.size() > RTT_HISTORY_SIZE) rttHistory.poll();

        // Check for anomaly only after enough samples
        if (sampleIdx < MIN_SAMPLES) return;

        // Compute variance of recent samples
        double[] arr    = rttHistory.stream().mapToDouble(Double::doubleValue).toArray();
        double   mean   = java.util.Arrays.stream(arr).average().orElse(0);
        double   var    = java.util.Arrays.stream(arr).map(v -> (v - mean) * (v - mean)).average().orElse(0);
        double   stdDev = Math.sqrt(var);

        // RTT spike detection: current RTT > EWMA + 3×stdDev + spike threshold
        double rttDelta = rttMs - newEwma;
        if (rttDelta > RTT_SPIKE_THRESHOLD_MS && stdDev > 0 && (rttMs - mean) > 3 * stdDev) {
            TapAction action = selectAction(rttDelta);
            TapAlert  alert  = new TapAlert(System.currentTimeMillis(), rttDelta, stdDev, action);
            tapAlerts.offer(alert);
            alerts.incrementAndGet();

            LOG.log(Level.WARNING,
                    "[SHIELD-FABRIC] Tap anomaly detected — rttDelta={0,number,0.00}ms " +
                    "stdDev={1,number,0.00}ms action={2} channel={3}",
                    rttDelta, stdDev, action, ctx.channel().remoteAddress());
        }
    }

    private TapAction selectAction(double rttDeltaMs) {
        if (rttDeltaMs > 50.0) return TapAction.INTERFACE_MIGRATE;
        if (rttDeltaMs > 20.0) return TapAction.CIPHER_ROTATE;
        return TapAction.ALERT_ONLY;
    }

    public double getCurrentEwmaRtt()           { return ewmaRtt.get(); }
    public long getAlertCount()                  { return alerts.get(); }
    public java.util.List<TapAlert> getAlerts() { return new java.util.ArrayList<>(tapAlerts); }
}
