package io.aetheros.control.policy;

import io.aetheros.core.policy.RoutingPolicy;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Atomic, hot-swappable holder for the active routing policy. The data
 * plane reads via {@link #current()} on every CONNECT (lock-free);
 * the control plane swaps via {@link #replace(RoutingPolicy, String)}.
 */
@Component
public final class PolicyHolder {

    private final AtomicReference<RoutingPolicy> policy =
            new AtomicReference<>(RoutingPolicy.ALLOW_ALL);
    private volatile String source = "{\"rules\":[{\"if\":{\"any\":true},\"action\":\"allow\"}]}";
    private volatile long version = 0L;

    public RoutingPolicy current() { return policy.get(); }
    public String source()         { return source; }
    public long version()          { return version; }

    public synchronized void replace(RoutingPolicy next, String source) {
        this.policy.set(next);
        this.source = source;
        this.version++;
    }
}
