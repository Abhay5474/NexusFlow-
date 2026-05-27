# AetherOS Feature Pack v2

Five high-visibility features that turn the data plane into a demo-able platform.

---

## 1. Chaos Engine

Lock-free `ChaosController` holds an immutable `Profile{enabled, laneKillProbability, injectedLatency, dnsDropProbability}`.

- `LaneManager.snapshotWithChaos()` samples per `pick()` — flips healthy → unhealthy probabilistically so strategies route around it.
- `UpstreamConnector` schedules completion through the **event loop** (`channel.eventLoop().schedule(...)`), never `Thread.sleep` — preserves the no-blocking-on-carriers invariant.
- API: `GET/POST/DELETE /api/chaos`, manual lane control via `/api/chaos/lanes/{id}/{kill,revive}`.

Demo: start a long-running download, slide "Lane Kill Probability" up to 60%, watch the lane bars flip red while throughput remains stable because the least-latency / weighted strategy reroutes instantly.

---

## 2. Geo-Topology Globe

`GeoLookupPort` in `aetheros-core`; `MaxMindGeoLookup` in `aetheros-geo` reads `GeoLite2-City.mmdb` if present (env `AETHEROS_GEOLITE2` or `data/GeoLite2-City.mmdb`).

- Resolved IPs emit a second forensics event with `lat/lon/country/city/ip` in `tags`.
- Frontend `GeoGlobe` (`react-globe.gl`) subscribes to the WS stream, dedupes by `connId|ip`, and draws animated arcs from a configurable home point. Arcs TTL at 8s.

Graceful no-DB mode: if the MMDB file is absent, lookups return empty — proxy still works, globe shows nothing until the operator drops the file in.

---

## 3. Heuristic Classifier v2

`HeuristicClassifier` per-flow:

1. Seed from `(port, SNI)` heuristic (`TrafficClassifier.classify`).
2. Refine from observed read rate over a 250ms sliding window: `>250 KB/s` → `STREAMING`; bursty `<1.5 KB` → `INTERACTIVE`.
3. SNI substring hints upgrade to `STREAMING` immediately (`nflxvideo`, `googlevideo`, `cloudfront`, …).

`ClassDistribution` aggregates bytes + flows per class via `LongAdder`. API `/api/bandshifter/distribution` powers the dashboard pie chart.

---

## 4. Time-Travel Forensics (DVR)

H2 in-memory database + Spring Data JPA. `ForensicsRecorder` subscribes to the multicast `ForensicsEventPort`, buffers into a bounded queue, drains on a virtual thread, persists 256-event batches in one transaction.

- Schema indexes on `ts` and `(stage, ts)` for fast windowed scans.
- `@Scheduled` retention eviction (`aetheros.dvr.retention-hours`).
- `GET /api/dvr/events?fromMs=...&toMs=...&limit=...` capped at 5,000.
- Frontend `DvrPanel` is a slider scrubbing the past hour; the same data plane that powers the live console replays from H2.

Backpressure: drop-oldest into the recorder queue means a DB stall *never* slows the proxy.

---

## 5. Zero-Trust Routing DSL

`RoutingPolicy` is a single-method port returning `RoutingDecision` (`Allow | Deny(reason) | PinLane(id)`).

`PolicyHolder` is an `AtomicReference<RoutingPolicy>` — swap is one CAS, no JVM restart.

`PolicyDsl` compiles JSON rules into a chain of predicates (top-to-bottom, first match wins). Conditions: `domainEndsWith`, `domainContains`, `domainEquals`, `port`, `portIn`, `afterHour`, `beforeHour`, `any`. Actions: `allow | deny | pin`.

The SOCKS5 request router evaluates **before** DNS resolution — deny is REP `0x02 FORBIDDEN`, no upstream contact.

Frontend: Monaco editor with live install via `PUT /api/policy/dsl`; success bumps `version` so dashboards know the policy moved.

### Example policy

```json
{
  "rules": [
    { "if": { "port": 25 },                              "action": "deny",  "reason": "smtp" },
    { "if": { "domainEndsWith": ".internal" },           "action": "pin",   "lane": 0 },
    { "if": { "domainContains": "ads", "afterHour": 22 }, "action": "deny", "reason": "night-block" },
    { "if": { "any": true },                             "action": "allow" }
  ]
}
```
