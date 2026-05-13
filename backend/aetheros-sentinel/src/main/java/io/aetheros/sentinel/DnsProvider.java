package io.aetheros.sentinel;

import java.net.InetSocketAddress;

/**
 * A public DNS upstream Sentinel races against. {@code id} is bounded
 * (Prometheus-safe label); {@code endpoint} is the UDP/53 address.
 */
public record DnsProvider(String id, InetSocketAddress endpoint) {

    public static DnsProvider of(String id, String host) {
        return new DnsProvider(id, new InetSocketAddress(host, 53));
    }

    public static final DnsProvider GOOGLE     = of("google",     "8.8.8.8");
    public static final DnsProvider CLOUDFLARE = of("cloudflare", "1.1.1.1");
    public static final DnsProvider QUAD9      = of("quad9",      "9.9.9.9");
    public static final DnsProvider OPENDNS    = of("opendns",    "208.67.222.222");
    public static final DnsProvider ADGUARD    = of("adguard",    "94.140.14.14");
}
