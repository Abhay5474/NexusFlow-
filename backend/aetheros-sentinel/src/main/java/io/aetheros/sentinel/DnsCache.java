package io.aetheros.sentinel;

import io.aetheros.core.dns.DnsAnswer;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single-flight DNS cache. Bounded TTL clamps misbehaving upstreams.
 * Concurrent lookups for the same name share a single in-flight resolve
 * (handled at the resolver layer — this cache only stores completed answers).
 */
public final class DnsCache {

    private static final Duration MAX_TTL = Duration.ofMinutes(5);
    private static final Duration MIN_TTL = Duration.ofSeconds(5);

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public Optional<DnsAnswer> get(String name) {
        Entry e = entries.get(name);
        if (e == null) return Optional.empty();
        if (Instant.now().isAfter(e.expiresAt)) {
            entries.remove(name, e);
            return Optional.empty();
        }
        return Optional.of(e.answer);
    }

    public void put(DnsAnswer answer) {
        Duration ttl = clamp(answer.ttl());
        entries.put(answer.name(), new Entry(answer, answer.resolvedAt().plus(ttl)));
    }

    public int size() { return entries.size(); }

    private static Duration clamp(Duration ttl) {
        if (ttl == null || ttl.compareTo(MIN_TTL) < 0) return MIN_TTL;
        if (ttl.compareTo(MAX_TTL) > 0) return MAX_TTL;
        return ttl;
    }

    private record Entry(DnsAnswer answer, Instant expiresAt) {}
}
