package io.aetheros.bandshifter;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Rolling per-class byte counters powering the dashboard pie chart.
 * Thread-safe; cheap enough to update on every relay write.
 */
public final class ClassDistribution {

    private final Map<TrafficClass, LongAdder> bytes = new EnumMap<>(TrafficClass.class);
    private final Map<TrafficClass, LongAdder> flows = new EnumMap<>(TrafficClass.class);

    public ClassDistribution() {
        for (TrafficClass c : TrafficClass.values()) {
            bytes.put(c, new LongAdder());
            flows.put(c, new LongAdder());
        }
    }

    public void addBytes(TrafficClass c, long n) { bytes.get(c).add(n); }
    public void incFlow(TrafficClass c)          { flows.get(c).increment(); }

    public Map<TrafficClass, Long> bytesSnapshot() {
        Map<TrafficClass, Long> out = new EnumMap<>(TrafficClass.class);
        bytes.forEach((k, v) -> out.put(k, v.sum()));
        return out;
    }

    public Map<TrafficClass, Long> flowsSnapshot() {
        Map<TrafficClass, Long> out = new EnumMap<>(TrafficClass.class);
        flows.forEach((k, v) -> out.put(k, v.sum()));
        return out;
    }
}
