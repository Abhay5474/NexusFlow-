package io.aetheros.nexus.chaos;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-wide chaos profile read by the data plane (UpstreamConnector,
 * LaneManager). The control plane mutates this; the data plane samples it
 * per operation. All fields are immutable records to keep reads lock-free.
 */
public final class ChaosController {

    public record Profile(
            boolean enabled,
            double laneKillProbability,      // 0..1, sampled per pick
            Duration injectedLatency,        // added to every connect()
            double dnsDropProbability        // 0..1, sampled per DNS race winner
    ) {
        public static final Profile OFF = new Profile(false, 0.0, Duration.ZERO, 0.0);
    }

    private final AtomicReference<Profile> active = new AtomicReference<>(Profile.OFF);

    public Profile get() { return active.get(); }
    public void set(Profile p) { active.set(p == null ? Profile.OFF : p); }
    public void disable() { active.set(Profile.OFF); }
}
