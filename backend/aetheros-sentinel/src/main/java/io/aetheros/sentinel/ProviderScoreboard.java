package io.aetheros.sentinel;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Per-provider rolling reliability score. EWMA over latency + win rate +
 * error rate. Thread-safe; updated from any thread that observes an answer.
 *
 * <pre>
 *   score(p) = w1 * winRate(p) + w2 * (1 - errRate(p)) - w3 * normLatency(p)
 * </pre>
 */
public final class ProviderScoreboard {

    private static final double ALPHA = 0.1;          // EWMA smoothing
    private static final double LATENCY_NORM_MS = 250.0;

    private static final double W_WIN     = 0.5;
    private static final double W_SUCCESS = 0.4;
    private static final double W_LATENCY = 0.3;

    private final Map<String, Stats> stats = new ConcurrentHashMap<>();

    public void recordWin(String providerId, Duration latency) {
        stats.computeIfAbsent(providerId, k -> new Stats()).recordWin(latency);
    }

    public void recordLoss(String providerId) {
        stats.computeIfAbsent(providerId, k -> new Stats()).recordLoss();
    }

    public void recordError(String providerId) {
        stats.computeIfAbsent(providerId, k -> new Stats()).recordError();
    }

    public double score(String providerId) {
        Stats s = stats.get(providerId);
        return s == null ? 0.5 : s.score();
    }

    private static final class Stats {
        private final LongAdder wins = new LongAdder();
        private final LongAdder losses = new LongAdder();
        private final LongAdder errors = new LongAdder();
        private volatile double ewmaLatencyMs = LATENCY_NORM_MS / 2.0;

        void recordWin(Duration latency) {
            wins.increment();
            ewmaLatencyMs = ALPHA * latency.toMillis() + (1 - ALPHA) * ewmaLatencyMs;
        }
        void recordLoss()  { losses.increment(); }
        void recordError() { errors.increment(); }

        double score() {
            long w = wins.sum(), l = losses.sum(), e = errors.sum();
            long attempts = w + l + e;
            if (attempts == 0) return 0.5;
            double winRate = (double) w / attempts;
            double errRate = (double) e / attempts;
            double normLat = Math.min(1.0, ewmaLatencyMs / LATENCY_NORM_MS);
            return W_WIN * winRate + W_SUCCESS * (1 - errRate) - W_LATENCY * normLat;
        }
    }
}
