package io.aetheros.core.lane;

import java.time.Duration;

/**
 * Logical forwarding lane. Lanes are the unit over which Nexus does
 * scatter-gather; each carries an EWMA health score driven by
 * {@link #observedLatency()} and {@link #errorRate()}.
 */
public record Lane(
        int id,
        String label,
        Duration observedLatency,
        double errorRate,
        boolean healthy
) {
    public double healthScore() {
        double latencyPenalty = Math.min(1.0, observedLatency.toMillis() / 500.0);
        return Math.max(0.0, 1.0 - 0.6 * latencyPenalty - 0.4 * errorRate);
    }
}
