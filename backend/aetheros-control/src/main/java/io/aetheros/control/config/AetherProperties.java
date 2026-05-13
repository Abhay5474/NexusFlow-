package io.aetheros.control.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "aetheros")
public class AetherProperties {

    private Proxy proxy = new Proxy();
    private Sentinel sentinel = new Sentinel();
    private Nexus nexus = new Nexus();
    private Shaper shaper = new Shaper();

    public Proxy getProxy() { return proxy; }
    public Sentinel getSentinel() { return sentinel; }
    public Nexus getNexus() { return nexus; }
    public Shaper getShaper() { return shaper; }

    public static class Proxy {
        private String bindHost = "127.0.0.1";
        private int bindPort = 1080;
        public String getBindHost() { return bindHost; }
        public void setBindHost(String v) { this.bindHost = v; }
        public int getBindPort() { return bindPort; }
        public void setBindPort(int v) { this.bindPort = v; }
    }
    public static class Sentinel {
        private List<String> providers = List.of("google", "cloudflare", "quad9", "opendns", "adguard");
        public List<String> getProviders() { return providers; }
        public void setProviders(List<String> v) { this.providers = v; }
    }
    public static class Nexus {
        private int lanes = 4;
        private String strategy = "least-latency";
        public int getLanes() { return lanes; }
        public void setLanes(int v) { this.lanes = v; }
        public String getStrategy() { return strategy; }
        public void setStrategy(String v) { this.strategy = v; }
    }
    public static class Shaper {
        private long bytesPerSecond = 10_000_000L;
        private long burstBytes = 1_000_000L;
        public long getBytesPerSecond() { return bytesPerSecond; }
        public void setBytesPerSecond(long v) { this.bytesPerSecond = v; }
        public long getBurstBytes() { return burstBytes; }
        public void setBurstBytes(long v) { this.burstBytes = v; }
    }
}
