package io.aetheros.nexus;

import io.aetheros.core.lane.Lane;
import io.aetheros.core.lane.LaneSelectionStrategy;
import io.aetheros.nexus.strategies.LaneStrategies;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the current view of lanes and the active selection strategy. Lanes
 * are observable, mutable records: latency/error are updated by connect
 * callbacks; lane records themselves are replaced atomically.
 *
 * <p>The control plane mutates strategy via {@link #setStrategy(String)};
 * the data plane reads via {@link #pick()}.
 */
public final class LaneManager {

    private static final double EWMA_ALPHA = 0.2;

    private final Map<Integer, MutableLane> state = new ConcurrentHashMap<>();
    private final AtomicReference<LaneSelectionStrategy> strategy =
            new AtomicReference<>(LaneStrategies.leastLatency());
    private final AtomicReference<String> strategyName = new AtomicReference<>("least-latency");

    public LaneManager(int laneCount) {
        for (int i = 0; i < laneCount; i++) {
            state.put(i, new MutableLane(i, "lane-" + i, Duration.ofMillis(50), 0.0, true));
        }
    }

    public List<Lane> snapshot() {
        return state.values().stream()
                .sorted((a, b) -> Integer.compare(a.id, b.id))
                .map(MutableLane::toRecord)
                .toList();
    }

    public Lane pick() {
        return strategy.get().pick(snapshot());
    }

    public void recordSuccess(int laneId, Duration latency) {
        var l = state.get(laneId);
        if (l == null) return;
        synchronized (l) {
            double newMs = EWMA_ALPHA * latency.toMillis() + (1 - EWMA_ALPHA) * l.latency.toMillis();
            l.latency = Duration.ofMillis(Math.max(1, (long) newMs));
            l.errorRate = (1 - EWMA_ALPHA) * l.errorRate;
            l.healthy = true;
        }
    }

    public void recordError(int laneId) {
        var l = state.get(laneId);
        if (l == null) return;
        synchronized (l) {
            l.errorRate = EWMA_ALPHA + (1 - EWMA_ALPHA) * l.errorRate;
            if (l.errorRate > 0.5) l.healthy = false;
        }
    }

    public boolean setStrategy(String name) {
        LaneSelectionStrategy next = switch (name) {
            case "round-robin"       -> LaneStrategies.roundRobin();
            case "least-latency"     -> LaneStrategies.leastLatency();
            case "weighted"          -> LaneStrategies.weightedByHealth();
            case "congestion-aware"  -> LaneStrategies.congestionAware(1.0, 1.0);
            default -> null;
        };
        if (next == null) return false;
        strategy.set(next);
        strategyName.set(name);
        return true;
    }

    public String currentStrategy() { return strategyName.get(); }

    private static final class MutableLane {
        final int id;
        final String label;
        volatile Duration latency;
        volatile double errorRate;
        volatile boolean healthy;
        MutableLane(int id, String label, Duration latency, double err, boolean healthy) {
            this.id = id; this.label = label; this.latency = latency;
            this.errorRate = err; this.healthy = healthy;
        }
        Lane toRecord() { return new Lane(id, label, latency, errorRate, healthy); }
    }
}
