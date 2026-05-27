package io.aetheros.control.config;

import io.aetheros.bandshifter.Shaper;
import io.aetheros.core.dns.DnsResolverPort;
import io.aetheros.core.forensics.ForensicsEventPort;
import io.aetheros.nexus.LaneManager;
import io.aetheros.nexus.UpstreamConnector;
import io.aetheros.proxy.Socks5PipelineInitializer;
import io.aetheros.proxy.Socks5RequestRouter;
import io.aetheros.proxy.Socks5Server;
import io.aetheros.sentinel.*;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * Spring wiring: assembles the data plane and binds it to the control
 * plane via ports. Data-plane Netty threads start lazily in
 * {@link #startProxy()} after all beans are constructed.
 */
@Configuration
@EnableConfigurationProperties(AetherProperties.class)
public class AetherWiring {

    private static final Map<String, DnsProvider> KNOWN = Map.of(
            "google",     DnsProvider.GOOGLE,
            "cloudflare", DnsProvider.CLOUDFLARE,
            "quad9",      DnsProvider.QUAD9,
            "opendns",    DnsProvider.OPENDNS,
            "adguard",    DnsProvider.ADGUARD);

    @Bean
    public List<DnsProvider> providers(AetherProperties props) {
        return props.getSentinel().getProviders().stream()
                .map(KNOWN::get)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    @Bean(destroyMethod = "close")
    public NettyProviderQuery providerQuery() {
        return new NettyProviderQuery();
    }

    @Bean
    public DnsCache dnsCache() { return new DnsCache(); }

    @Bean
    public ProviderScoreboard scoreboard() { return new ProviderScoreboard(); }

    @Bean
    public SentinelMetrics sentinelMetrics(MeterRegistry reg,
                                           ProviderScoreboard sb,
                                           List<DnsProvider> providers) {
        SentinelMetrics m = new SentinelMetrics(reg);
        m.bindScoreboard(sb, providers);
        return m;
    }

    @Bean
    public DnsResolverPort dnsResolver(List<DnsProvider> providers,
                                       NettyProviderQuery query,
                                       DnsCache cache,
                                       ProviderScoreboard scoreboard,
                                       SentinelMetrics metrics) {
        // Wrap the query so every observation flows through metrics.
        ProviderQuery instrumented = (p, name, timeout) ->
                query.query(p, name, timeout).whenComplete((ans, ex) -> {
                    if (ex != null) metrics.recordError(p.id());
                    else metrics.recordWin(ans.provider(), ans.latency());
                });
        return new RacingDnsResolver(providers, instrumented, cache, scoreboard);
    }

    @Bean
    public LaneManager laneManager(AetherProperties props) {
        var lm = new LaneManager(props.getNexus().getLanes());
        lm.setStrategy(props.getNexus().getStrategy());
        return lm;
    }

    @Bean
    public UpstreamConnector upstreamConnector(LaneManager lanes) {
        return new UpstreamConnector(lanes);
    }

    @Bean
    public Shaper shaper(AetherProperties props) {
        return new Shaper(props.getShaper().getBytesPerSecond(), props.getShaper().getBurstBytes());
    }

    @Bean
    public Socks5RequestRouter router(DnsResolverPort dns,
                                      UpstreamConnector connector,
                                      Shaper shaper,
                                      ForensicsEventPort forensics) {
        return new Socks5RequestRouter(dns, connector, shaper, forensics);
    }

    @Bean
    public Socks5PipelineInitializer pipelineInitializer(Socks5RequestRouter router) {
        return new Socks5PipelineInitializer(router);
    }

    @Bean(destroyMethod = "stop")
    public Socks5Server socks5Server(AetherProperties props, Socks5PipelineInitializer init) {
        return new Socks5Server(props.getProxy().getBindHost(), props.getProxy().getBindPort(), init);
    }

    @Bean
    public org.springframework.boot.ApplicationRunner startProxyRunner(Socks5Server proxy) {
        return args -> {
            try {
                proxy.start();
            } catch (Exception e) {
                throw new RuntimeException("Failed to start SOCKS5 proxy", e);
            }
        };
    }

}
