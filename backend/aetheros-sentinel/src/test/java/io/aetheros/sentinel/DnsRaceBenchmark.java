package io.aetheros.sentinel;

import io.aetheros.core.dns.DnsAnswer;
import io.aetheros.core.dns.DnsResolverPort;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * JMH harness: CompletableFuture racer vs Reactor racer, against a
 * deterministic ProviderQuery that injects per-provider latency.
 *
 * Run:  mvn -pl aetheros-sentinel test-compile exec:exec -Dexec.classpathScope=test \
 *           -Dexec.executable=java -Dexec.args="-cp %classpath org.openjdk.jmh.Main DnsRaceBenchmark"
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class DnsRaceBenchmark {

    DnsResolverPort cfResolver;
    DnsResolverPort reactorResolver;

    @Setup
    public void setup() {
        ProviderQuery q = (provider, name, timeout) -> CompletableFuture.supplyAsync(() -> {
            long ms = switch (provider.id()) {
                case "google"     -> 50;
                case "cloudflare" -> 10;
                case "quad9"      -> 80;
                case "opendns"    -> 30;
                default           -> 200;
            };
            try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            return new DnsAnswer(name, List.of(InetAddress.getLoopbackAddress()),
                    Duration.ofSeconds(60), provider.id(),
                    Duration.ofMillis(ms), Instant.now());
        });

        var providers = List.of(
                DnsProvider.GOOGLE, DnsProvider.CLOUDFLARE,
                DnsProvider.QUAD9, DnsProvider.OPENDNS);

        cfResolver      = new RacingDnsResolver(providers, q, new DnsCache(), new ProviderScoreboard());
        reactorResolver = new ReactorDnsResolver(providers, q, new DnsCache(), new ProviderScoreboard());
    }

    @Benchmark
    public void completableFutureRace(Blackhole bh) throws Exception {
        bh.consume(cfResolver.resolve("bench.example").toCompletableFuture().get());
    }

    @Benchmark
    public void reactorRace(Blackhole bh) throws Exception {
        bh.consume(reactorResolver.resolve("bench.example").toCompletableFuture().get());
    }
}
