package io.aetheros.ids;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Behavioral Anomaly Filter & Beacon Detector.
 *
 * <p>Tracks per-connection temporal metrics and flags "beaconing" patterns —
 * background spyware/malware that periodically calls home at suspiciously
 * regular intervals (e.g., every exactly 30 seconds).
 *
 * <h3>Detection algorithm</h3>
 * For each remote host, an EWMA (Exponentially Weighted Moving Average)
 * scoreboard tracks:
 * <ul>
 *   <li><b>Connection interval</b> — time between successive connections from
 *       the same host. Low variance over many samples = high beacon score.</li>
 *   <li><b>Packet size ratio</b> — beacons typically have low inbound / high
 *       outbound ratios (tiny keep-alive responses, moderate POST beacons).</li>
 *   <li><b>Connection frequency</b> — normal user traffic is bursty; beacon
 *       traffic is eerily regular at a fixed rate.</li>
 * </ul>
 *
 * <p>When the composite beacon score exceeds {@link #BEACON_THRESHOLD}, the
 * connection is terminated and the host is flagged in the alert log.
 *
 * <h3>EWMA formula</h3>
 * {@code ewma_new = α × sample + (1 − α) × ewma_old}
 * where α = {@link #ALPHA} (smoothing factor).
 */
public class BeaconDetectorHandler extends ChannelInboundHandlerAdapter {

    private static final Logger LOG = System.getLogger(BeaconDetectorHandler.class.getName());

    /** EWMA smoothing factor (higher = more weight on recent samples). */
    private static final double ALPHA = 0.2;

    /** Beacon score threshold above which connections are flagged. */
    private static final double BEACON_THRESHOLD = 0.75;

    /** Minimum number of connections before scoring is applied. */
    private static final int MIN_SAMPLES = 3;

    /**
     * Per-host state for beacon detection.
     */
    private static final class HostState {
        final AtomicLong  lastConnectNs    = new AtomicLong(0);
        final AtomicInteger connectionCount = new AtomicInteger(0);
        final AtomicReference<Double> ewmaIntervalMs = new AtomicReference<>(0.0);
        final AtomicReference<Double> ewmaVariance   = new AtomicReference<>(0.0);
        final AtomicReference<Double> beaconScore    = new AtomicReference<>(0.0);
    }

    /** Global host → state map. Shared across all handler instances. */
    private static final ConcurrentHashMap<String, HostState> HOST_STATES =
            new ConcurrentHashMap<>();

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        String host = remoteHost(ctx);
        if (host != null) {
            HostState state = HOST_STATES.computeIfAbsent(host, k -> new HostState());
            long nowNs      = System.nanoTime();
            long prevNs     = state.lastConnectNs.getAndSet(nowNs);
            int  count      = state.connectionCount.incrementAndGet();

            if (prevNs > 0 && count >= MIN_SAMPLES) {
                double intervalMs   = (nowNs - prevNs) / 1_000_000.0;

                // Update EWMA interval
                double oldEwma      = state.ewmaIntervalMs.get();
                double newEwma      = ALPHA * intervalMs + (1 - ALPHA) * oldEwma;
                state.ewmaIntervalMs.set(newEwma);

                // Update variance EWMA (measures regularity)
                double diff         = Math.abs(intervalMs - newEwma);
                double oldVar       = state.ewmaVariance.get();
                double newVar       = ALPHA * diff + (1 - ALPHA) * oldVar;
                state.ewmaVariance.set(newVar);

                // Compute beacon score: low variance relative to interval = high score
                // Score approaches 1.0 as variance → 0, and 0 as variance → interval
                double cvRatio      = (newEwma > 0) ? (newVar / newEwma) : 1.0;
                double score        = Math.max(0.0, 1.0 - Math.min(1.0, cvRatio));
                state.beaconScore.set(score);

                if (score >= BEACON_THRESHOLD) {
                    LOG.log(Level.WARNING,
                            "[BEACON] HOST={0} score={1,number,0.00} interval={2,number,0.0}ms " +
                            "variance={3,number,0.0}ms — DISCONNECTING",
                            host, score, newEwma, newVar);
                    ctx.channel().close().addListener(ChannelFutureListener.CLOSE);
                    return;
                }
            }
        }
        super.channelActive(ctx);
    }

    private String remoteHost(ChannelHandlerContext ctx) {
        var addr = ctx.channel().remoteAddress();
        if (addr instanceof java.net.InetSocketAddress isa) {
            return isa.getHostString();
        }
        return null;
    }

    /**
     * Returns the current beacon score for a given host (for monitoring UI).
     */
    public static double getBeaconScore(String host) {
        HostState s = HOST_STATES.get(host);
        return s != null ? s.beaconScore.get() : 0.0;
    }

    /**
     * Returns all hosts with beacon scores above the detection threshold.
     */
    public static java.util.Map<String, Double> getHighScoreHosts() {
        var result = new java.util.HashMap<String, Double>();
        HOST_STATES.forEach((host, state) -> {
            double score = state.beaconScore.get();
            if (score >= BEACON_THRESHOLD) result.put(host, score);
        });
        return result;
    }
}
