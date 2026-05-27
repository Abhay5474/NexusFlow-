package io.aetheros.control.config;

import io.aetheros.bandshifter.ClassDistribution;
import io.aetheros.bandshifter.Shaper;
import io.aetheros.control.policy.PolicyHolder;
import io.aetheros.core.dns.DnsResolverPort;
import io.aetheros.core.forensics.ForensicsEventPort;
import io.aetheros.core.geo.GeoLookupPort;
import io.aetheros.geo.MaxMindGeoLookup;
import io.aetheros.nexus.LaneManager;
import io.aetheros.nexus.UpstreamConnector;
import io.aetheros.nexus.chaos.ChaosController;
import io.aetheros.proxy.Socks5PipelineInitializer;
import io.aetheros.proxy.Socks5RequestRouter;
import io.aetheros.proxy.Socks5Server;
import io.aetheros.sentinel.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.List;
import java.util.Map;

@Configuration
@EnableConfigurationProperties(AetherProperties.class)
@EnableScheduling
public class AetherWiring {

    private static final Map<String, DnsProvider> KNOWN = Map.of(
            "google",     DnsProvider.GOOGLE,
            "cloudflare", DnsProvider.CLOUDFLARE,
            "quad9",      DnsProvider.QUAD9,
            "opendns",    DnsProvider.OPENDNS,
            "adguard",    DnsProvider.ADGUARD);

    @Bean public List<DnsProvider> providers(AetherProperties props) {
        return props.getSentinel().getProviders().stream()
                .map(KNOWN::get).filter(java.util.Objects::nonNull).toList();
    }

    @Bean(destroyMethod = "close") public NettyProviderQuery providerQuery() { return new NettyProviderQuery(); }
    @Bean public DnsCache dnsCache() { return new DnsCache(); }
    @Bean public ProviderScoreboard scoreboard() { return new ProviderScoreboard(); }

    @Bean public SentinelMetrics sentinelMetrics(MeterRegistry r, ProviderScoreboard sb, List<DnsProvider> ps) {
        var m = new SentinelMetrics(r); m.bindScoreboard(sb, ps); return m;
    }

    @Bean public DnsResolverPort dnsResolver(List<DnsProvider> providers,
                                             NettyProviderQuery query,
                                             DnsCache cache,
                                             ProviderScoreboard scoreboard,
                                             SentinelMetrics metrics) {
        ProviderQuery instrumented = (p, name, timeout) ->
                query.query(p, name, timeout).whenComplete((ans, ex) -> {
                    if (ex != null) metrics.recordError(p.id());
                    else metrics.recordWin(ans.provider(), ans.latency());
                });
        return new RacingDnsResolver(providers, instrumented, cache, scoreboard);
    }

    @Bean public ChaosController chaosController() { return new ChaosController(); }

    @Bean public LaneManager laneManager(AetherProperties props, ChaosController chaos) {
        var lm = new LaneManager(props.getNexus().getLanes());
        lm.setStrategy(props.getNexus().getStrategy());
        lm.setChaos(chaos);
        return lm;
    }

    @Bean public UpstreamConnector upstreamConnector(LaneManager lanes, ChaosController chaos) {
        var c = new UpstreamConnector(lanes); c.setChaos(chaos); return c;
    }

    @Bean public Shaper shaper(AetherProperties props) {
        return new Shaper(props.getShaper().getBytesPerSecond(), props.getShaper().getBurstBytes());
    }

    @Bean public ClassDistribution classDistribution() { return new ClassDistribution(); }

    @Bean(destroyMethod = "close") public GeoLookupPort geoLookup() {
        return new MaxMindGeoLookup(MaxMindGeoLookup.resolveDefaultPath());
    }

    @Bean public Socks5RequestRouter router(DnsResolverPort dns,
                                            UpstreamConnector connector,
                                            Shaper shaper,
                                            ForensicsEventPort forensics,
                                            GeoLookupPort geo,
                                            PolicyHolder policyHolder,
                                            ClassDistribution distribution) {
        return new Socks5RequestRouter(dns, connector, shaper, forensics,
                geo, policyHolder::current, distribution);
    }

    @Bean public Socks5PipelineInitializer pipelineInitializer(Socks5RequestRouter r) {
        return new Socks5PipelineInitializer(r);
    }

    @Bean(destroyMethod = "stop")
    public Socks5Server socks5Server(AetherProperties props, Socks5PipelineInitializer init) {
        return new Socks5Server(props.getProxy().getBindHost(), props.getProxy().getBindPort(), init);
    }

    @Bean
    public ApplicationRunner startProxyRunner(Socks5Server proxy) {
        return args -> {
            try {
                proxy.start();
            } catch (Exception e) {
                throw new RuntimeException("Failed to start SOCKS5 proxy", e);
            }
        };
    }
}
