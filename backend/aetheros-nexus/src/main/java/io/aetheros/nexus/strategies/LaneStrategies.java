package io.aetheros.nexus.strategies;

import io.aetheros.core.lane.Lane;
import io.aetheros.core.lane.LaneSelectionStrategy;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Pluggable lane selection strategies. Hot-swap via the control plane.
 */
public final class LaneStrategies {

    private LaneStrategies() {}

    public static LaneSelectionStrategy roundRobin() {
        AtomicInteger cursor = new AtomicInteger();
        return lanes -> {
            var healthy = lanes.stream().filter(Lane::healthy).toList();
            if (healthy.isEmpty()) throw new IllegalStateException("no healthy lanes");
            return healthy.get(Math.floorMod(cursor.getAndIncrement(), healthy.size()));
        };
    }

    public static LaneSelectionStrategy leastLatency() {
        return lanes -> lanes.stream()
                .filter(Lane::healthy)
                .min(Comparator.comparing(Lane::observedLatency))
                .orElseThrow(() -> new IllegalStateException("no healthy lanes"));
    }

    /** Weighted random selection by {@link Lane#healthScore()}. */
    public static LaneSelectionStrategy weightedByHealth() {
        return lanes -> {
            List<Lane> healthy = lanes.stream().filter(Lane::healthy).toList();
            if (healthy.isEmpty()) throw new IllegalStateException("no healthy lanes");
            double total = healthy.stream().mapToDouble(Lane::healthScore).sum();
            double r = ThreadLocalRandom.current().nextDouble() * total;
            double acc = 0;
            for (Lane l : healthy) {
                acc += l.healthScore();
                if (r <= acc) return l;
            }
            return healthy.get(healthy.size() - 1);
        };
    }

    public static LaneSelectionStrategy congestionAware(double latencyWeight,
                                                        double errorWeight) {
        return lanes -> lanes.stream()
                .filter(Lane::healthy)
                .min(Comparator.comparingDouble(l ->
                        latencyWeight * l.observedLatency().toMillis()
                        + errorWeight * l.errorRate() * 1000.0))
                .orElseThrow(() -> new IllegalStateException("no healthy lanes"));
    }
}
