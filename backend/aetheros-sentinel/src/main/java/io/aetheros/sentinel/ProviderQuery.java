package io.aetheros.sentinel;

import io.aetheros.core.dns.DnsAnswer;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

/**
 * Strategy seam for the actual wire-level DNS query. Real implementation
 * is Netty's {@code DnsNameResolver} per-provider; tests inject deterministic
 * latency/failure profiles via this interface.
 */
@FunctionalInterface
public interface ProviderQuery {
    CompletionStage<DnsAnswer> query(DnsProvider provider, String name, Duration timeout);
}
