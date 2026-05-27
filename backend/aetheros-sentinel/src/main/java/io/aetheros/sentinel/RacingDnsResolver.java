package io.aetheros.sentinel;

import io.aetheros.core.dns.DnsAnswer;
import io.aetheros.core.dns.DnsResolverPort;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * CompletableFuture-based race resolver. See {@code ARCHITECTURE.md §4} for
 * the algorithm. A Reactor variant lives at {@link ReactorDnsResolver}; both
 * are kept so the JMH harness can compare ergonomics and tail-latency.
 *
 * <p>Threading: each provider query runs on a virtual thread. Cancellation is
 * propagated to losers via {@link CompletableFuture#cancel(boolean)}.
 */
public final class RacingDnsResolver implements DnsResolverPort {

    private static final Duration FAST_DEADLINE     = Duration.ofMillis(1500);
    private static final Duration EXTENDED_DEADLINE = Duration.ofMillis(10000);
    private static final Duration PER_QUERY_TIMEOUT = Duration.ofMillis(5000);

    private final List<DnsProvider> providers;
    private final ProviderQuery providerQuery;
    private final DnsCache cache;
    private final ProviderScoreboard scoreboard;
    private final Executor executor;
    private final SingleFlight<String, DnsAnswer> singleFlight = new SingleFlight<>();

    public RacingDnsResolver(List<DnsProvider> providers,
                             ProviderQuery providerQuery,
                             DnsCache cache,
                             ProviderScoreboard scoreboard) {
        this(providers, providerQuery, cache, scoreboard,
             Executors.newVirtualThreadPerTaskExecutor());
    }

    public RacingDnsResolver(List<DnsProvider> providers,
                             ProviderQuery providerQuery,
                             DnsCache cache,
                             ProviderScoreboard scoreboard,
                             Executor executor) {
        this.providers = List.copyOf(providers);
        this.providerQuery = providerQuery;
        this.cache = cache;
        this.scoreboard = scoreboard;
        this.executor = executor;
    }

    @Override
    public CompletionStage<DnsAnswer> resolve(String name) {
        var cached = cache.get(name);
        if (cached.isPresent()) return CompletableFuture.completedFuture(cached.get());
        return singleFlight.run(name, this::raceOnce);
    }

    private CompletionStage<DnsAnswer> raceOnce(String name) {
        var ordered = providers.stream()
                .sorted((a, b) -> Double.compare(scoreboard.score(b.id()), scoreboard.score(a.id())))
                .toList();

        @SuppressWarnings("unchecked")
        CompletableFuture<DnsAnswer>[] attempts = ordered.stream()
                .map(p -> CompletableFuture
                        .supplyAsync(() -> providerQuery.query(p, name, PER_QUERY_TIMEOUT)
                                                        .toCompletableFuture()
                                                        .join(), executor)
                        .orTimeout(PER_QUERY_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                        .whenComplete((ans, ex) -> {
                            if (ex != null) scoreboard.recordError(p.id());
                        }))
                .toArray(CompletableFuture[]::new);

        CompletableFuture<DnsAnswer> winner = new CompletableFuture<>();

        for (CompletableFuture<DnsAnswer> attempt : attempts) {
            attempt.whenComplete((ans, ex) -> {
                if (ans != null && !winner.isDone()) {
                    if (winner.complete(ans)) {
                        scoreboard.recordWin(ans.provider(), ans.latency());
                        cache.put(ans);
                        for (CompletableFuture<DnsAnswer> loser : attempts) {
                            if (loser != attempt) loser.cancel(true);
                        }
                    }
                }
            });
        }

        CompletableFuture.allOf(attempts).whenComplete((v, ex) -> {
            if (!winner.isDone()) {
                winner.completeExceptionally(
                        new DnsResolveException("All providers failed for " + name));
            }
        });

        return winner.orTimeout(EXTENDED_DEADLINE.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public static final class DnsResolveException extends RuntimeException {
        public DnsResolveException(String msg) { super(msg); }
    }
}
