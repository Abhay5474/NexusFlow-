package io.aetheros.core.dns;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Resolved DNS answer returned by Sentinel. {@code provider} identifies the
 * race winner; {@code latency} is the wall-clock query time for scoring.
 */
public record DnsAnswer(
        String name,
        List<InetAddress> addresses,
        Duration ttl,
        String provider,
        Duration latency,
        Instant resolvedAt
) {}
