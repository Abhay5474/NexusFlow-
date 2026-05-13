package io.aetheros.sentinel;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Coalesces concurrent lookups for the same key onto a single in-flight
 * computation. Eliminates the thundering-herd problem when many SOCKS
 * connections request the same hostname simultaneously.
 */
public final class SingleFlight<K, V> {

    private final ConcurrentHashMap<K, CompletableFuture<V>> inflight = new ConcurrentHashMap<>();

    public CompletionStage<V> run(K key, Function<K, CompletionStage<V>> loader) {
        CompletableFuture<V> existing = inflight.get(key);
        if (existing != null) return existing;

        CompletableFuture<V> created = new CompletableFuture<>();
        CompletableFuture<V> winner = inflight.putIfAbsent(key, created);
        if (winner != null) return winner;

        loader.apply(key).whenComplete((v, ex) -> {
            inflight.remove(key, created);
            if (ex != null) created.completeExceptionally(ex);
            else created.complete(v);
        });
        return created;
    }
}
