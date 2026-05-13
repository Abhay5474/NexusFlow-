package io.aetheros.control.api;

import io.aetheros.core.dns.DnsAnswer;
import io.aetheros.core.dns.DnsResolverPort;
import io.aetheros.sentinel.DnsProvider;
import io.aetheros.sentinel.ProviderScoreboard;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sentinel")
public class SentinelController {

    private final DnsResolverPort resolver;
    private final ProviderScoreboard scoreboard;
    private final List<DnsProvider> providers;

    public SentinelController(DnsResolverPort resolver,
                              ProviderScoreboard scoreboard,
                              List<DnsProvider> providers) {
        this.resolver = resolver;
        this.scoreboard = scoreboard;
        this.providers = providers;
    }

    @GetMapping("/providers")
    public List<Map<String, Object>> providers() {
        return providers.stream()
                .map(p -> Map.<String, Object>of(
                        "id", p.id(),
                        "endpoint", p.endpoint().toString(),
                        "score", scoreboard.score(p.id())))
                .toList();
    }

    @GetMapping("/resolve")
    public Mono<Map<String, Object>> resolve(@RequestParam String name) {
        return Mono.fromCompletionStage(resolver.resolve(name))
                .map(this::view)
                .onErrorResume(e -> Mono.just(Map.of("error", String.valueOf(e.getMessage()))));
    }

    private Map<String, Object> view(DnsAnswer a) {
        return Map.of(
                "name", a.name(),
                "provider", a.provider(),
                "latencyMs", a.latency().toMillis(),
                "ttlSeconds", a.ttl().toSeconds(),
                "addresses", a.addresses().stream().map(java.net.InetAddress::getHostAddress).toList());
    }
}
