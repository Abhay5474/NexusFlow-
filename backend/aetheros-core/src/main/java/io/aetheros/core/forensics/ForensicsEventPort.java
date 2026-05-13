package io.aetheros.core.forensics;

import reactor.core.publisher.Flux;

/**
 * Outbound port: data plane publishes events, control plane subscribes.
 * The implementation is a Reactor {@code Sinks.Many.multicast()} with
 * drop-oldest backpressure — slow WS clients must never stall the proxy.
 */
public interface ForensicsEventPort {
    void emit(ForensicsEvent event);

    Flux<ForensicsEvent> stream();
}
