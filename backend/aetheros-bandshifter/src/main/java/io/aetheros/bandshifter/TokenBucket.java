package io.aetheros.bandshifter;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Lock-free token bucket. {@code capacity} is the burst size (tokens);
 * {@code refillPerSecond} is the steady-state rate. Each token represents
 * one byte (callers may scale to packets).
 *
 * <p>Used by the Band-Shifter to pace writes per traffic category without
 * touching event-loop scheduling.
 */
public final class TokenBucket {

    private final long capacity;
    private final double refillPerNano;

    private final AtomicLong tokens;
    private volatile long lastRefillNanos;

    public TokenBucket(long capacity, long refillPerSecond) {
        this.capacity = capacity;
        this.refillPerNano = refillPerSecond / 1_000_000_000.0;
        this.tokens = new AtomicLong(capacity);
        this.lastRefillNanos = System.nanoTime();
    }

    public boolean tryConsume(long n) {
        refill();
        while (true) {
            long cur = tokens.get();
            if (cur < n) return false;
            if (tokens.compareAndSet(cur, cur - n)) return true;
        }
    }

    private void refill() {
        long now = System.nanoTime();
        long last = lastRefillNanos;
        long elapsed = now - last;
        if (elapsed <= 0) return;
        long add = (long) (elapsed * refillPerNano);
        if (add <= 0) return;
        // best-effort; precise atomic packing optional
        lastRefillNanos = now;
        while (true) {
            long cur = tokens.get();
            long next = Math.min(capacity, cur + add);
            if (tokens.compareAndSet(cur, next)) return;
        }
    }

    public long available() { refill(); return tokens.get(); }
    public long capacity()  { return capacity; }
}
