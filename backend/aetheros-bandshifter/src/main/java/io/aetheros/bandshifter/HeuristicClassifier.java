package io.aetheros.bandshifter;

/**
 * Per-connection traffic classifier. Starts with a port+SNI heuristic
 * (cheap, available before any bytes flow), then refines based on observed
 * read-rate behaviour: sustained high throughput → STREAMING/BULK,
 * bursty small writes → INTERACTIVE.
 *
 * <p>Heuristics deliberately overlap; the classifier converges as more
 * samples arrive. Thread-safety: instances are per-connection (one Netty
 * channel = one classifier), so plain fields are safe.
 */
public final class HeuristicClassifier {

    /** Known CDN/streaming SNI substrings (lowercased). */
    private static final String[] STREAMING_HINTS = {
            "nflxvideo", "googlevideo", "twitch", "ttvnw", "media-amazon",
            "akamaized", "cloudfront", "fbcdn", "video"
    };

    private static final long INTERACTIVE_MAX_BURST = 1500;     // bytes
    private static final long STREAMING_MIN_RATE_BPS = 250_000; // sustained

    private TrafficClass current;
    private long bytesSinceWindow;
    private long windowStartedNanos;

    public HeuristicClassifier(int port, String sni) {
        this.current = TrafficClassifier.classify(port, sni);
        if (sni != null) {
            String s = sni.toLowerCase();
            for (String hint : STREAMING_HINTS) {
                if (s.contains(hint)) { this.current = TrafficClass.STREAMING; break; }
            }
        }
        this.windowStartedNanos = System.nanoTime();
    }

    /** Feed observed bytes; returns the (possibly updated) classification. */
    public TrafficClass observe(int bytes) {
        bytesSinceWindow += bytes;
        long elapsed = System.nanoTime() - windowStartedNanos;
        if (elapsed < 250_000_000L) return current;     // 250ms window

        long bps = (long) (bytesSinceWindow * 1_000_000_000.0 / elapsed);
        TrafficClass next = current;

        if (bps >= STREAMING_MIN_RATE_BPS) {
            next = (current == TrafficClass.WEB || current == TrafficClass.UNKNOWN)
                    ? TrafficClass.STREAMING : current;
        } else if (bytesSinceWindow < INTERACTIVE_MAX_BURST && current == TrafficClass.UNKNOWN) {
            next = TrafficClass.INTERACTIVE;
        }

        // sliding-window reset
        bytesSinceWindow = 0;
        windowStartedNanos = System.nanoTime();
        current = next;
        return current;
    }

    public TrafficClass current() { return current; }
}
