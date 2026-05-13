package io.aetheros.bandshifter;

import java.util.EnumMap;
import java.util.Map;

/**
 * Per-class token bucket shaper. Holds one bucket per {@link TrafficClass};
 * callers ask {@link #admit} whether N bytes may flow now.
 *
 * <p>Class capacities reflect WFQ weights; aggregate steady-state rate is
 * the sum across classes (typical config: ~80% of measured uplink).
 */
public final class Shaper {

    private final Map<TrafficClass, TokenBucket> buckets = new EnumMap<>(TrafficClass.class);

    public Shaper(long totalBytesPerSecond, long burstBytes) {
        int totalWeight = 0;
        for (TrafficClass c : TrafficClass.values()) totalWeight += TrafficClassifier.weightOf(c);
        for (TrafficClass c : TrafficClass.values()) {
            long share = totalBytesPerSecond * TrafficClassifier.weightOf(c) / totalWeight;
            buckets.put(c, new TokenBucket(burstBytes, Math.max(1, share)));
        }
    }

    public boolean admit(TrafficClass c, int bytes) {
        return buckets.get(c).tryConsume(bytes);
    }

    public long available(TrafficClass c) { return buckets.get(c).available(); }
}
