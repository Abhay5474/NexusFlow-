# AetherOS Architecture — Deep Technical Blueprint

> Audience: Principal networking / distributed systems / JVM performance engineers.
> Goal: explain *why* each design choice exists, not just *what* it is.

---

## 0. Design Tenets

| Tenet | Consequence |
|---|---|
| Separate control plane from data plane | Spring Boot never touches a packet; Netty never touches JPA. |
| Bytes flow on event loops, decisions flow on virtual threads | Avoid blocking I/O threads; isolate stateful logic. |
| Strategy + Observer everywhere routing decisions exist | Hot-swap algorithms (round-robin → weighted-latency) without redeploy. |
| Backpressure is a first-class signal | A slow lane must slow producers, not buffer infinitely. |
| Every decision emits a metric | Forensics stream is derived from Micrometer, not bolted on. |

---

## 1. High-Level Architecture

```
                          ┌──────────────────────────────────────────────┐
   ┌─────────────┐        │                CONTROL PLANE                 │
   │  React NOC  │◄──WS──►│  Spring Boot 3 / WebFlux                     │
   │  Dashboard  │  REST  │  • Policy API     • Diagnostics API          │
   └─────────────┘        │  • WS forensics   • Prometheus scrape        │
                          │  • Lane orchestrator (DTO ↔ Domain port)     │
                          └──────────────┬───────────────────────────────┘
                                         │ in-process port (interface)
                                         │ event bus (Project Reactor Sinks)
                          ┌──────────────▼───────────────────────────────┐
                          │                 DATA PLANE                   │
                          │                                              │
                          │   ┌──────────────┐    ┌────────────────┐     │
   Client ───SOCKS5──────►│   │ Netty Proxy  │───►│ Chameleon TLS  │     │
   (browser / app)        │   │ (event loops)│    │ metadata parse │     │
                          │   └──────┬───────┘    └────────┬───────┘     │
                          │          │                     │             │
                          │          ▼                     ▼             │
                          │   ┌──────────────┐    ┌────────────────┐     │
                          │   │  Sentinel    │    │  Band-Shifter  │     │
                          │   │  DNS race    │    │  QoS classify  │     │
                          │   │  consensus   │    │  + token bucket│     │
                          │   └──────┬───────┘    └────────┬───────┘     │
                          │          │                     │             │
                          │          └──────────┬──────────┘             │
                          │                     ▼                        │
                          │            ┌────────────────┐                │
                          │            │  Nexus Lane    │── upstream ───►│ ISP
                          │            │  multiplexer   │     N TCP      │
                          │            │  (1..N lanes)  │   connections  │
                          │            └────────────────┘                │
                          └──────────────────────────────────────────────┘
```

### Why two planes?

A single JVM hosts both, but they communicate only through **ports** (hex
arch) — `RoutingPolicyPort`, `ForensicsEventPort`, `LaneOrchestratorPort`.
This keeps Netty handlers free of Spring lifecycle weight and lets the data
plane be benchmarked in isolation (`jmh` harnesses pin handlers without ever
booting Spring).

### Threading model overview

| Layer | Thread type | Why |
|---|---|---|
| Netty accept/IO | Platform threads (event loops) | Loom-on-event-loop is an anti-pattern; selector wakeups must be predictable. |
| SOCKS5 handshake state machine | Netty handler (event loop) | Tiny, non-blocking, ByteBuf-driven. |
| Outbound connect / DNS / TLS metadata fanout | **Virtual threads** (`Executors.newVirtualThreadPerTaskExecutor`) | Naturally blocking semantics; one VT per lane attempt. |
| Spring REST / WS | Reactor Netty (WebFlux) | Backpressure-aware streaming of forensic events. |
| Metrics flush | Micrometer scheduler | Decoupled from hot path. |

Rule: **bytes never block; decisions may.**

---

## 2. SOCKS5 Handshake Lifecycle

RFC 1928. The Netty pipeline implements this as a small state machine with
discrete decoders for each phase, replaced after each phase completes.

```
TCP accept
   │
   ▼
┌─────────────────────────────────────────────────────────────────┐
│ Phase 1: METHOD NEGOTIATION                                     │
│  Client → Server:                                               │
│    +----+----------+----------+                                 │
│    |VER | NMETHODS | METHODS  |                                 │
│    +----+----------+----------+                                 │
│    | 1  |    1     | 1 to 255 |                                 │
│  Server → Client:                                               │
│    +----+--------+                                              │
│    |VER | METHOD |   (0x00 NO_AUTH, 0x02 USER/PASS, 0xFF none)  │
│    +----+--------+                                              │
└─────────────────────────────────────────────────────────────────┘
   │
   ▼ if METHOD == 0x02
┌─────────────────────────────────────────────────────────────────┐
│ Phase 2: SUB-NEGOTIATION (RFC 1929 USER/PASS, optional)         │
│  +----+------+----------+------+----------+                     │
│  |VER | ULEN |   UNAME  | PLEN |  PASSWD  |                     │
│  +----+------+----------+------+----------+                     │
│  Server replies 0x00 success / 0x01..0xFF failure.              │
└─────────────────────────────────────────────────────────────────┘
   │
   ▼
┌─────────────────────────────────────────────────────────────────┐
│ Phase 3: REQUEST                                                │
│  +----+-----+-------+------+----------+----------+              │
│  |VER | CMD |  RSV  | ATYP | DST.ADDR | DST.PORT |              │
│  +----+-----+-------+------+----------+----------+              │
│  | 1  |  1  | 0x00  |  1   | Variable |    2     |              │
│   CMD : 0x01 CONNECT | 0x02 BIND | 0x03 UDP ASSOCIATE           │
│   ATYP: 0x01 IPv4    | 0x03 DOMAIN | 0x04 IPv6                  │
└─────────────────────────────────────────────────────────────────┘
   │
   ▼
┌─────────────────────────────────────────────────────────────────┐
│ Phase 4: RESOLVE + UPSTREAM CONNECT                             │
│  • If ATYP=DOMAIN  → Sentinel.resolve(domain) [virtual thread]  │
│  • Pick Nexus lane → connect(addr) [virtual thread]             │
│  • Hand outbound channel back to event loop                     │
└─────────────────────────────────────────────────────────────────┘
   │
   ▼
┌─────────────────────────────────────────────────────────────────┐
│ Phase 5: REPLY                                                  │
│  +----+-----+-------+------+----------+----------+              │
│  |VER | REP |  RSV  | ATYP | BND.ADDR | BND.PORT |              │
│  +----+-----+-------+------+----------+----------+              │
│   REP : 0x00 OK | 0x01 general fail | 0x03 net unreachable | …  │
└─────────────────────────────────────────────────────────────────┘
   │
   ▼
┌─────────────────────────────────────────────────────────────────┐
│ Phase 6: RELAY                                                  │
│  Bidirectional ByteBuf piping:                                  │
│    client ⇄ Chameleon (peek TLS ClientHello on first N bytes)   │
│           ⇄ Band-Shifter (token bucket per category)            │
│           ⇄ Nexus lane (chosen upstream channel)                │
│  Backpressure: setAutoRead(false) on the slow side.             │
└─────────────────────────────────────────────────────────────────┘
```

### Failure mapping (REP codes)

| Cause | REP |
|---|---|
| DNS race timeout, all providers failed | 0x04 (host unreachable) |
| Upstream `connect()` refused | 0x05 (connection refused) |
| Policy denies destination | 0x02 (rule-set disallowed) |
| Unsupported ATYP / CMD | 0x07 / 0x08 |

---

## 3. Netty Pipeline Blueprint

```
ChannelPipeline (inbound from SOCKS client)
┌──────────────────────────────────────────────────────────────────┐
│ idleStateHandler         (read 60s, write 60s, all 120s)         │
│ loggingHandler           (TRACE only, gated by config)           │
│ socks5MethodDecoder      (replaced after phase 1)                │
│ socks5AuthDecoder        (installed if METHOD==0x02, removed)    │
│ socks5RequestDecoder     (replaced after phase 3)                │
│ socks5RequestHandler     (executes phases 4–5, then self-removes)│
│ ── pipeline mutates here ───────────────────────────────────────│
│ chameleonClientHelloPeek (passive, 1st flight only, ≤16 KiB)    │
│ bandShifterShaper        (token bucket; drops or paces writes)   │
│ nexusLaneSelector        (chooses outbound Channel per write)    │
│ relayHandler             (zero-copy where possible: CompositeBB) │
└──────────────────────────────────────────────────────────────────┘
                                  ▲
                                  │
              ChannelOption.AUTO_READ toggled by relayHandler
              to propagate backpressure both directions.
```

### ByteBuf discipline

- Use **pooled direct** buffers (`PooledByteBufAllocator.DEFAULT`).
- `retain()`/`release()` audited; `ResourceLeakDetector.Level.PARANOID` in test.
- Never call `.array()` on a direct buffer; always `readBytes(dst, off, len)` or slice.
- `CompositeByteBuf` for scatter-gather across Nexus lane reassembly — avoids
  copy when lanes deliver out-of-order chunks that must be re-ordered before
  the relay write.

### Replacing handlers vs. branching

After each SOCKS phase, the decoder for that phase is **removed** rather than
left as a no-op. This is both faster (one fewer hop per byte in the relay
phase) and easier to reason about — a leftover decoder with stale state is
the #1 source of SOCKS proxy bugs in the wild.

---

## 4. Sentinel — DNS Race Consensus Algorithm

### Problem

A single DNS upstream is a SPOF and a latency floor. Public resolvers (Google
`8.8.8.8`, Cloudflare `1.1.1.1`, Quad9 `9.9.9.9`, OpenDNS `208.67.222.222`,
AdGuard `94.140.14.14`) have wildly different latency depending on geography,
peering, and time of day.

### Algorithm (race + consensus + cache)

```
resolve(name):
  if cache.hit(name) and not expired: return cache.get(name)

  attempt = new RaceAttempt(name)
  for provider in healthyProviders():            # health-scored subset
      attempt.fanout(provider, timeout=80ms)     # async UDP query

  winner = attempt.firstValidWithin(150ms)       # earliest answer
  if winner == null:
      winner = attempt.bestEffortBy(400ms)       # extended deadline

  if winner == null:
      return Failure(NXDOMAIN_OR_TIMEOUT)

  consensusCheck(winner, attempt.late())         # log disagreement
  cache.put(name, winner, ttl = min(answer.ttl, MAX_TTL))
  scoreboard.record(provider, latency, success)
  return winner
```

### Two implementations, benchmarked head-to-head

**A. `CompletableFuture` racer**

```java
List<CompletableFuture<DnsAnswer>> futs = providers.stream()
    .map(p -> CompletableFuture.supplyAsync(() -> query(p, name), virtualExec)
                               .orTimeout(80, MILLISECONDS))
    .toList();

return CompletableFuture
    .anyOf(futs.toArray(CompletableFuture[]::new))
    .thenApply(o -> (DnsAnswer) o)
    .whenComplete((ans, ex) -> futs.forEach(f -> f.cancel(true)));
```

**B. Reactor racer**

```java
return Flux.fromIterable(providers)
    .flatMap(p -> Mono.fromCallable(() -> query(p, name))
                      .timeout(Duration.ofMillis(80))
                      .onErrorResume(e -> Mono.empty())
                      .subscribeOn(Schedulers.boundedElastic()))
    .next()                            // first emission wins
    .switchIfEmpty(extendedFallback);
```

We benchmark **both** under JMH with:

- cold cache, 5 providers, 1k QPS
- one provider injected with 200ms latency
- one provider injected with packet loss

Reported metrics: p50/p95/p99 resolve latency, cache hit ratio, per-provider
win rate, EWMA reliability score (`α=0.1` over rolling window).

### Health scoring

```
score(p) = w1 * winRate(p) + w2 * (1 - errRate(p)) - w3 * normLatency(p)
```

Providers below threshold are demoted (queried last, half weight) for a
cooldown of 30s, then probed.

### Why race, not round-robin?

Round-robin minimizes *average* load on upstreams but inherits the worst
provider's tail. Racing trades a small amount of upstream QPS for a dramatic
p99 improvement — the whole point of this layer.

---

## 5. Virtual Thread Integration

### Where VTs live

```
Netty event loop  ─── platform threads (NEVER virtual)
        │
        │ ctx.executor().execute(...)   ← stays on event loop
        │
        ▼
┌──────────────────────────────────────────┐
│ Decision boundary                        │
│   • DNS resolve                          │
│   • Upstream TCP connect                 │
│   • Policy lookup (may hit MySQL)        │
│   • Forensics enrichment                 │
└──────────────┬───────────────────────────┘
               │ submit to virtualExec
               ▼
   Executors.newVirtualThreadPerTaskExecutor()
               │
               │ on completion: ctx.channel().eventLoop().execute(...)
               ▼
         back on event loop for the next ByteBuf hop
```

### Structured concurrency for the DNS race

```java
try (var scope = new StructuredTaskScope.ShutdownOnSuccess<DnsAnswer>()) {
    for (var p : providers) scope.fork(() -> query(p, name));
    scope.joinUntil(Instant.now().plusMillis(150));
    return scope.result();      // first successful, others cancelled
}
```

Benefits:

- Cancellation is transitive (no leaked UDP socket reads).
- Lifecycle is lexically scoped — easy to reason about, easy to audit.
- Pairs cleanly with virtual threads (one fork = one VT, dirt cheap).

### Why not put Netty itself on virtual threads?

Two reasons:

1. Selector code path uses `synchronized` extensively in JDK internals;
   pinning a VT defeats the purpose.
2. Event-loop affinity (one channel ↔ one thread) is what makes Netty's
   lock-free handler model work. Migrating ByteBufs across threads is
   exactly what we don't want.

### Benchmark plan

| Scenario | Platform threads | Virtual threads | Reactor |
|---|---|---|---|
| 10k idle SOCKS connections | OOM around 4–6k | flat memory | flat memory |
| 1k active CONNECTs/sec | ctx-switch heavy | low overhead | lowest overhead, hardest to debug |
| Mixed (5k idle + 200 active) | poor | best ergonomics | best throughput, complex |

JMH + async-profiler + JFR. Report carrier-thread pinning events (should be
zero on the hot path).

---

## 6. Module Roadmap (incremental delivery)

| # | Module | Lands with |
|---|---|---|
| 1 | `aetheros-core` ports + domain | This commit |
| 2 | `aetheros-sentinel` | DNS race + JMH harness + dashboard hook |
| 3 | `aetheros-proxy` | Netty SOCKS5 server + handshake state machine |
| 4 | `aetheros-chameleon` | ClientHello/SNI ByteBuf parser (read-only) |
| 5 | `aetheros-bandshifter` | Token bucket + WFQ simulation |
| 6 | `aetheros-nexus` | Lane multiplexer + scatter-gather reassembly |
| 7 | `aetheros-control` | REST + WS forensics + Prometheus |
| 8 | `frontend/` | React Flow topology + live charts |

Each module ships with `docs/modules/<name>.md` containing its own deep
reasoning (algorithms, data structures, failure modes, benchmarks).

---

## 7. Observability Contract

Every routing decision emits a `ForensicsEvent`:

```java
record ForensicsEvent(
    Instant ts,
    String  connectionId,   // ULID
    Stage   stage,          // SOCKS_HANDSHAKE | DNS | TLS_PEEK | LANE_PICK | RELAY | CLOSE
    String  decision,       // e.g. "lane=2 reason=least-latency"
    Map<String,Object> tags // latencyMs, bytesIn, bytesOut, provider, sni, ...
) {}
```

Flow:

```
handler → ForensicsEventPort (Reactor Sinks.Many.multicast)
        → Micrometer counters/timers (cardinality-bounded tags)
        → WebSocket broadcaster (backpressure: DROP_OLDEST)
        → Prometheus /actuator/prometheus
```

Cardinality discipline: `connectionId` never becomes a Prometheus label;
it's a WS-only field. Labels are bounded sets (stage, provider, laneId).

---

## 8. Security & Scope Boundaries (hard rules)

- No modification of TLS payloads — Chameleon is **read-only metadata**.
- No DNS spoofing — Sentinel only **selects** among honest answers.
- No traffic to third parties without explicit user opt-in config.
- SOCKS5 bound to `127.0.0.1` by default; LAN binding requires an env flag.
- Policy engine has a hard-coded deny list for known abuse patterns
  (e.g. SMTP-on-25 to arbitrary upstreams) — enforced at REQUEST phase.

---

Next: scaffolding lands in this commit; Module 2 (Sentinel) ships next with
its JMH harness and dashboard wiring.
