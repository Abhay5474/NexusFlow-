# AetherOS Frontend (NOC Dashboard)

React + TypeScript + Tailwind + Framer Motion + Recharts.

## Dev

```bash
npm install
npm run dev          # http://localhost:5173 — proxies /api and /ws/forensics to :8080
```

Layout:

- **Lanes**: live lane health, latency, error rate; strategy hot-swap via PUT /api/lanes/strategy.
- **Throughput**: 60s rolling byte counter aggregated from CLOSE forensics events.
- **DNS Providers**: live scoreboard from /api/sentinel/providers.
- **Forensics Stream**: WebSocket /ws/forensics — every routing decision in real time.
