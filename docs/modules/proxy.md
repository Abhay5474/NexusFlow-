# Proxy — SOCKS5 Data Plane

See `ARCHITECTURE.md §§2–3` for the system-level context.

## Pipeline timeline

```
ACCEPT
  └─ idle, trace, socks5encoder, phase1-decode, phase1, router

Phase 1 (method negotiation) finishes:
  └─ phase1-decode → phase3-decode  (replace)
  └─ phase1 removed

Phase 3 (CONNECT) — router resolves DNS, picks lane, connects upstream:
  └─ on success: writes REPLY, then mutates pipeline to relay mode

Relay mode:
  client:    idle, trace, chameleon (one-shot SNI peek), client-writability, relay-out
  upstream:  up-writability, relay-in
```

## Backpressure

Implemented by `RelayHandler` + `WritabilityResumer`:

1. Both sides start with `AUTO_READ = false` (set by `UpstreamConnector`).
2. `channelActive` issues one explicit `read()`.
3. After each peer write succeeds, if the peer is writable, we `read()` again.
4. If the peer's outbound buffer fills, `peer.isWritable()` flips to `false`;
   we stop reading from our side.
5. The `WritabilityResumer` sits on the *peer* and resumes `partner.read()`
   when the peer drains.

This is the canonical Netty proxy pattern. It cannot deadlock because every
`read()` is followed by either a successful flush (which re-issues `read()`)
or a writability-change event (which re-issues `read()`).

## Band-Shifter integration

`RelayHandler` consults the shared `Shaper` per write. Denied writes are
rescheduled on the event loop after 1ms — never block the carrier thread.

## Chameleon integration

A `ClientHelloPeekHandler` is installed *once* on the client side. It runs
on the very first inbound `ByteBuf`, emits the SNI (if any) as a forensics
event, and removes itself. The peek is read-only and capped at 16 KiB.

## Failure paths

| Stage | Failure | Action |
|---|---|---|
| DNS race | all providers fail | REP 0x04, close |
| Upstream connect | refused / timeout | REP 0x05, lane marked errored |
| Relay | peer write fails | close both sides, emit CLOSE forensics event |

## Integration test

`Socks5RoundTripIT` boots an in-process echo server + the full proxy,
performs a manual SOCKS5 handshake, and verifies bidirectional relay.
