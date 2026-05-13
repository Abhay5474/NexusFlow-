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

## Status

This is the **initial scaffolding commit**. See `ARCHITECTURE.md` for the
complete blueprint covering:

1. High-level architecture diagram
2. SOCKS5 handshake lifecycle
3. Netty pipeline blueprint
4. Sentinel DNS race-winner algorithm
5. Virtual Thread integration design

Modules are landed incrementally; each module ships with its own deep
technical reasoning document under `docs/modules/`.

## Build (once modules land)

```bash
cd backend && ./mvnw clean verify
cd ../frontend && npm install && npm run dev
```

## Safety & Scope

AetherOS is strictly educational. It does **not**:

- Modify third-party encrypted traffic
- Bypass lawful network restrictions
- Implement DPI evasion or domain-fronting abuse
- Perform unauthorized packet surgery

All "adaptive routing" and "stealth transport" features are simulations on
loopback / opt-in local traffic for performance and architecture study.
