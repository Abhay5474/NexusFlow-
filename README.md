# AetherOS — NexusFlow

> A Software-Defined Autonomous Networking Overlay between the OS and the ISP.
> Educational, research-oriented, production-grade systems engineering project.

AetherOS is a programmable intelligent routing fabric that sits between local
applications and the upstream ISP. It is **not** a VPN, **not** a CRUD app, and
**not** a censorship-evasion tool. It is a Java 21 / Netty / Spring Boot
platform that demonstrates:

- Low-level networking (SOCKS5, NIO, ByteBuf framing, TLS metadata parsing)
- JVM concurrency (Virtual Threads + structured concurrency + Reactor)
- Distributed routing logic (parallel DNS consensus, multiplexed lanes)
- Real-time observability (Micrometer → Prometheus → WebSocket forensics)
- Production architecture (hexagonal, event-driven, strategy/proxy/observer)

```
 Browser / App
      │
      ▼
┌──────────────────────────────────────────────┐
│              AetherOS Engine                 │
│  ┌────────────┐  ┌────────────┐  ┌─────────┐ │
│  │ Sentinel   │  │ Chameleon  │  │ Nexus   │ │
│  │ DNS race   │  │ TLS meta + │  │ Lane    │ │
│  │ consensus  │  │ adaptive   │  │ MUX /   │ │
│  │            │  │ routing    │  │ DEMUX   │ │
│  └─────┬──────┘  └─────┬──────┘  └────┬────┘ │
│        └───── Band-Shifter QoS ───────┘      │
│                                              │
│             Netty Data Plane                 │
│             Spring Control Plane             │
└──────────────────────┬───────────────────────┘
                       ▼
                      ISP
                       ▼
                    Internet
```

## Repository Layout

```
nexusflow/
├── ARCHITECTURE.md              # Deep technical design (start here)
├── backend/                     # Java 21 multi-module Maven project
│   ├── pom.xml                  # Root POM, BOM, dependency mgmt
│   ├── aetheros-core/           # Domain model, ports, shared primitives
│   ├── aetheros-sentinel/       # Parallel DNS consensus engine
│   ├── aetheros-chameleon/      # TLS ClientHello / SNI parsing
│   ├── aetheros-bandshifter/    # Traffic classification + QoS sim
│   ├── aetheros-nexus/          # Multiplexed lane engine
│   ├── aetheros-proxy/          # Netty SOCKS5 data plane
│   └── aetheros-control/        # Spring Boot control plane (REST + WS)
└── frontend/                    # React + TS + Tailwind NOC dashboard
```

## Status — end-to-end working slice

All seven modules + control plane + dashboard are wired:

| Module | What ships |
|---|---|
| `aetheros-core` | Domain records, ports (DNS, forensics, lane). |
| `aetheros-sentinel` | Race resolver (CF + Reactor), Netty wire-level provider, EWMA scoreboard, TTL cache, single-flight, JMH harness, Micrometer bindings. |
| `aetheros-chameleon` | Read-only TLS ClientHello/SNI parser + one-shot Netty peek handler. |
| `aetheros-bandshifter` | Lock-free token bucket, traffic classifier, per-class WFQ shaper. |
| `aetheros-nexus` | `LaneManager` (mutable state, EWMA updates, hot-swap strategy), `UpstreamConnector`, four selection strategies. |
| `aetheros-proxy` | Full SOCKS5 server: phase decoders, request router, `RelayHandler` with auto-read backpressure + writability resumer, integration test. |
| `aetheros-geo` | MaxMind GeoLite2 reader (graceful no-MMDB fallback). |
| `aetheros-control` | Spring Boot bootstrap, lifecycle, `/api/lanes`, `/api/sentinel`, `/api/policy{,/dsl}`, `/api/chaos`, `/api/dvr`, `/api/bandshifter`, `/api/diagnostics`, `/ws/forensics`, Prometheus, H2 + JPA DVR. |
| `frontend/` | Vite + React + TS + Tailwind + Framer Motion + Recharts + react-globe.gl + Monaco editor; tabbed NOC with Overview · Globe · Chaos · Classify · DVR · Policy. |

See `docs/modules/features-v2.md` for the 5-feature deep dive (Chaos Engine,
3D Geo Globe, Heuristic Classifier v2, Time-Travel DVR, Zero-Trust DSL).

See `ARCHITECTURE.md` for the design blueprint and `docs/modules/*.md` for
per-module deep dives.

## Build & run

```bash
# Backend (control plane on :8080, SOCKS5 on :1080)
cd backend && mvn -B -DskipTests package
java --enable-preview -jar aetheros-control/target/aetheros-control-*.jar

# Frontend NOC (proxies /api and /ws/forensics to :8080)
cd frontend && npm install && npm run dev

# Docker
docker build -f backend/Dockerfile -t aetheros backend/
docker run --rm -p 8080:8080 -p 127.0.0.1:1080:1080 aetheros

# Try it
curl -x socks5h://127.0.0.1:1080 https://example.com -o /dev/null -v
```

## Safety & Scope

AetherOS is strictly educational. It does **not**:

- Modify third-party encrypted traffic
- Bypass lawful network restrictions
- Implement DPI evasion or domain-fronting abuse
- Perform unauthorized packet surgery

All "adaptive routing" and "stealth transport" features are simulations on
loopback / opt-in local traffic for performance and architecture study.
