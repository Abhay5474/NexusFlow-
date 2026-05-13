package io.aetheros.sentinel;

import io.aetheros.core.dns.DnsAnswer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.resolver.dns.SequentialDnsServerAddressStreamProvider;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Real wire-level DNS query implementation backed by Netty's
 * {@link DnsNameResolver}. One resolver per provider so each query targets
 * exactly one upstream (the default builder fans out to all configured
 * addresses, which would defeat the race).
 *
 * <p>Resolvers are cached per provider; the shared {@link EventLoopGroup}
 * is owned by this object and closed via {@link #close()}.
 */
public final class NettyProviderQuery implements ProviderQuery, AutoCloseable {

    private final EventLoopGroup group;
    private final Map<String, DnsNameResolver> resolvers = new ConcurrentHashMap<>();

    public NettyProviderQuery() {
        this.group = new NioEventLoopGroup(2);
    }

    @Override
    public CompletionStage<DnsAnswer> query(DnsProvider provider, String name, Duration timeout) {
        DnsNameResolver resolver = resolvers.computeIfAbsent(provider.id(), id ->
                new DnsNameResolverBuilder(group.next())
                        .channelType(NioDatagramChannel.class)
                        .nameServerProvider(new SequentialDnsServerAddressStreamProvider(provider.endpoint()))
                        .queryTimeoutMillis(timeout.toMillis())
                        .optResourceEnabled(false)
                        .build());

        long start = System.nanoTime();
        CompletableFuture<DnsAnswer> cf = new CompletableFuture<>();
        resolver.resolveAll(new DefaultDnsQuestion(name + ".", DnsRecordType.A))
                .addListener(f -> {
                    long elapsed = System.nanoTime() - start;
                    if (f.isSuccess()) {
                        @SuppressWarnings("unchecked")
                        var records = (List<io.netty.handler.codec.dns.DnsRecord>) f.getNow();
                        List<InetAddress> addrs = extractAddresses(records);
                        long minTtl = records.stream().mapToLong(r -> r.timeToLive()).min().orElse(60);
                        if (addrs.isEmpty()) {
                            cf.completeExceptionally(new RuntimeException("no A records for " + name));
                        } else {
                            cf.complete(new DnsAnswer(
                                    name, addrs,
                                    Duration.ofSeconds(minTtl),
                                    provider.id(),
                                    Duration.ofNanos(elapsed),
                                    Instant.now()));
                        }
                    } else {
                        cf.completeExceptionally(f.cause());
                    }
                });
        return cf;
    }

    private static List<InetAddress> extractAddresses(List<io.netty.handler.codec.dns.DnsRecord> recs) {
        var out = new ArrayList<InetAddress>(recs.size());
        for (var r : recs) {
            if (r instanceof io.netty.handler.codec.dns.DnsRawRecord raw
                    && r.type() == DnsRecordType.A) {
                byte[] b = new byte[4];
                raw.content().getBytes(raw.content().readerIndex(), b);
                try { out.add(InetAddress.getByAddress(b)); } catch (Exception ignored) {}
            }
        }
        return out;
    }

    @Override
    public void close() {
        resolvers.values().forEach(DnsNameResolver::close);
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS);
    }
}
