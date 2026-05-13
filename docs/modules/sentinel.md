# Sentinel — DNS Race Consensus

> Module deep-dive. See `ARCHITECTURE.md §4` for the system-level context.

## Why race?

DNS p50 from any single public resolver is usually fine (≤ 30ms). DNS p99
under packet loss, peering issues, or transient upstream load can spike to
500ms+. That tail is what poisons the perceived latency of an HTTPS
connection — DNS happens before the TCP handshake, before TLS, before HTTP.

Racing 3–5 honest upstreams costs (N-1) × QPS extra DNS traffic and
collapses the p99 to roughly the *fastest* upstream's p50.

## Algorithm

```
resolve(name):
  if cache.hit(name): return cached
  rank providers by scoreboard.score()                # best-first
  fan out N queries on virtual threads                # one VT each
  first valid answer wins → cancel losers
  if no answer in FAST_DEADLINE (150ms): extend to EXTENDED_DEADLINE (400ms)
  update scoreboard, cache answer, return
```

Cancellation is critical: losers must not continue to consume sockets or
file descriptors. `CompletableFuture#cancel(true)` interrupts the carrying
virtual thread; the Reactor variant uses subscription disposal.

## Implementations

| File | Approach |
|---|---|
| `RacingDnsResolver` | `CompletableFuture` + virtual threads + manual race wiring |
| `ReactorDnsResolver` | `Flux.flatMap(...).next()` over `Schedulers.boundedElastic()` |

Both implement `DnsResolverPort`. JMH harness (lands with M2.1) runs both
under:

- cold cache, 5 providers, 1k QPS
- one provider injected with 200ms latency
- one provider injected with packet loss

## Scoreboard

EWMA on per-provider latency (α=0.1); win/loss/error counters via
`LongAdder`. Score formula in `ProviderScoreboard.Stats#score()`. Providers
with score below threshold are *demoted*, not removed — a periodic probe
restores them after the cooldown.

## Cache

In-memory, TTL-clamped to [5s, 5min]. Single-flight is handled at the
resolver layer (planned: `ConcurrentHashMap<String, CompletableFuture<…>>`
keyed by name, removed on completion) so a thundering herd of inbound
connections to the same hostname triggers exactly one upstream race.

## Failure modes

| Failure | Behavior |
|---|---|
| All providers timeout in FAST_DEADLINE | extend to EXTENDED_DEADLINE |
| All providers fail in EXTENDED_DEADLINE | `DnsResolveException`, SOCKS REP 0x04 |
| One provider always slow | demoted by scoreboard, queried last |
| Provider returns NXDOMAIN while others return A | logged as consensus disagreement; first-valid wins |

## Metrics emitted

- `aetheros_dns_resolve_seconds{provider}` — timer, per winner
- `aetheros_dns_race_winrate{provider}` — counter
- `aetheros_dns_cache_hits` / `_misses`
- `aetheros_dns_provider_score{provider}` — gauge

Cardinality bounded by the fixed provider set.
