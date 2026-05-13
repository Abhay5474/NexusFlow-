package io.aetheros.sentinel;

import io.aetheros.core.dns.DnsAnswer;
import io.aetheros.core.dns.DnsResolverPort;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * Reactor-based race resolver. Functionally equivalent to
 * {@link RacingDnsResolver} but uses {@code Flux.flatMap(...).next()} for the
 * race. Kept for head-to-head benchmarking under JMH.
 */
public final class ReactorDnsResolver implements DnsResolverPort {

    private static final Duration PER_QUERY_TIMEOUT = Duration.ofMillis(80);
    private static final Duration EXTENDED_DEADLINE = Duration.ofMillis(400);

    private final List<DnsProvider> providers;
    private final ProviderQuery providerQuery;
    private final DnsCache cache;
    private final ProviderScoreboard scoreboard;

    public ReactorDnsResolver(List<DnsProvider> providers,
                              ProviderQuery providerQuery,
                              DnsCache cache,
                              ProviderScoreboard scoreboard) {
        this.providers = List.copyOf(providers);
        this.providerQuery = providerQuery;
        this.cache = cache;
        this.scoreboard = scoreboard;
    }

    @Override
    public CompletionStage<DnsAnswer> resolve(String name) {
        var cached = cache.get(name);
        if (cached.isPresent()) return Mono.just(cached.get()).toFuture();

        return Flux.fromIterable(providers)
                .flatMap(p -> Mono.fromCompletionStage(() -> providerQuery.query(p, name, PER_QUERY_TIMEOUT))
                                  .timeout(PER_QUERY_TIMEOUT)
                                  .doOnError(e -> scoreboard.recordError(p.id()))
                                  .onErrorResume(e -> Mono.empty())
                                  .subscribeOn(Schedulers.boundedElastic()))
                .next()
                .timeout(EXTENDED_DEADLINE)
                .doOnNext(ans -> {
                    scoreboard.recordWin(ans.provider(), ans.latency());
                    cache.put(ans);
                })
                .toFuture();
    }
}
