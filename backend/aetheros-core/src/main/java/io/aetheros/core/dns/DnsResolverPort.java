package io.aetheros.core.dns;

import java.util.concurrent.CompletionStage;

/**
 * Outbound port for name resolution. Implementations may race providers,
 * cache, or delegate to the JDK. The data plane depends only on this port.
 */
public interface DnsResolverPort {
    CompletionStage<DnsAnswer> resolve(String name);
}
