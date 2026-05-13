# Nexus — Multiplexed Lane Engine

A "lane" is a logical forwarding slot with its own health stats. The
current implementation runs one upstream TCP connection per lane per
SOCKS connection — the multi-connection scatter-gather variant lives
behind the same `LaneManager` API and is the next refinement.

## Components

- `LaneManager` — owns the mutable lane state, swaps strategies atomically.
- `UpstreamConnector` — uses `LaneManager.pick()` to choose a lane, then
  bootstraps a Netty `NioSocketChannel` on the client's event loop (one
  group, channel affinity preserved).
- `LaneStrategies` — pluggable selection: round-robin, least-latency,
  weighted-by-health, congestion-aware.

## EWMA updates

Every successful connect feeds latency back through:

```
latency_new = α * observed + (1-α) * latency_old        // α = 0.2
error_new   = (1-α) * error_old                         // success bleeds error
```

Failed connects:

```
error_new   = α + (1-α) * error_old
healthy = error < 0.5
```

A lane that crosses the unhealthy threshold is excluded from selection
until its error EWMA decays below 0.5 (via subsequent successes).

## Strategy semantics

| Strategy | Picks |
|---|---|
| round-robin | next healthy lane modulo count |
| least-latency | min `observedLatency` |
| weighted | random, weighted by `healthScore()` |
| congestion-aware | min `(α·latency + β·errorRate)` |

Hot-swap via `PUT /api/lanes/strategy` (control plane).
