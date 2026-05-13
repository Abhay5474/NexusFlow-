package io.aetheros.core.lane;

import java.util.List;

/**
 * Strategy pattern: round-robin, weighted, least-latency,
 * congestion-aware all implement this interface. Hot-swappable at
 * runtime via the control plane.
 */
@FunctionalInterface
public interface LaneSelectionStrategy {
    Lane pick(List<Lane> lanes);
}
