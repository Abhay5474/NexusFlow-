package io.aetheros.sentinel;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded-cardinality Micrometer bindings. Labels are the fixed provider
 * set — never the hostname (cardinality explosion).
 */
public final class SentinelMetrics {

    private final MeterRegistry registry;
    private final Map<String, Timer> winTimers = new ConcurrentHashMap<>();
    private final Map<String, Counter> errCounters = new ConcurrentHashMap<>();
    private final Counter cacheHits;
    private final Counter cacheMisses;

    public SentinelMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.cacheHits   = registry.counter("aetheros_dns_cache_hits");
        this.cacheMisses = registry.counter("aetheros_dns_cache_misses");
    }

    public void recordWin(String provider, Duration latency) {
        winTimers.computeIfAbsent(provider, p ->
                Timer.builder("aetheros_dns_resolve_seconds")
                     .tag("provider", p)
                     .publishPercentiles(0.5, 0.95, 0.99)
                     .register(registry))
                 .record(latency);
    }

    public void recordError(String provider) {
        errCounters.computeIfAbsent(provider, p ->
                Counter.builder("aetheros_dns_errors_total")
                       .tag("provider", p).register(registry))
                .increment();
    }

    public void cacheHit()  { cacheHits.increment(); }
    public void cacheMiss() { cacheMisses.increment(); }

    public void bindScoreboard(ProviderScoreboard scoreboard, Iterable<DnsProvider> providers) {
        for (DnsProvider p : providers) {
            registry.gauge("aetheros_dns_provider_score",
                    java.util.List.of(io.micrometer.core.instrument.Tag.of("provider", p.id())),
                    scoreboard, s -> s.score(p.id()));
        }
    }
}
