package io.aetheros.sentinel;

import io.aetheros.core.dns.DnsAnswer;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RacingDnsResolverTest {

    @Test
    void fastestProviderWins() throws Exception {
        ProviderQuery q = (provider, name, timeout) -> {
            long latencyMs = switch (provider.id()) {
                case "google"     -> 50;
                case "cloudflare" -> 10;   // winner
                case "quad9"      -> 80;
                default           -> 200;
            };
            return CompletableFuture.supplyAsync(() -> {
                try { Thread.sleep(latencyMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                return new DnsAnswer(
                        name,
                        List.of(InetAddress.getLoopbackAddress()),
                        Duration.ofSeconds(60),
                        provider.id(),
                        Duration.ofMillis(latencyMs),
                        Instant.now());
            });
        };

        var resolver = new RacingDnsResolver(
                List.of(DnsProvider.GOOGLE, DnsProvider.CLOUDFLARE, DnsProvider.QUAD9),
                q, new DnsCache(), new ProviderScoreboard());

        DnsAnswer ans = resolver.resolve("example.com")
                                .toCompletableFuture()
                                .get(2, TimeUnit.SECONDS);

        assertThat(ans.provider()).isEqualTo("cloudflare");
        assertThat(ans.name()).isEqualTo("example.com");
    }

    @Test
    void cacheShortCircuits() throws Exception {
        var cache = new DnsCache();
        cache.put(new DnsAnswer(
                "cached.test",
                List.of(InetAddress.getLoopbackAddress()),
                Duration.ofMinutes(1),
                "preloaded",
                Duration.ZERO,
                Instant.now()));

        ProviderQuery failIfCalled = (p, n, t) ->
                CompletableFuture.failedFuture(new AssertionError("should not query"));

        var resolver = new RacingDnsResolver(
                List.of(DnsProvider.GOOGLE), failIfCalled, cache, new ProviderScoreboard());

        DnsAnswer ans = resolver.resolve("cached.test")
                                .toCompletableFuture()
                                .get(1, TimeUnit.SECONDS);
        assertThat(ans.provider()).isEqualTo("preloaded");
    }
}
