package io.aetheros.control.forensics;

import io.aetheros.core.forensics.ForensicsEvent;
import io.aetheros.core.forensics.ForensicsEventPort;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Multicast forensics bus. {@code DROP_OLDEST} backpressure is deliberate —
 * slow WS clients must never stall the proxy hot path.
 */
@Component
public final class ReactorForensicsBus implements ForensicsEventPort {

    private final Sinks.Many<ForensicsEvent> sink =
            Sinks.many().multicast().onBackpressureBuffer(1024, false);

    @Override
    public void emit(ForensicsEvent event) {
        sink.tryEmitNext(event);
    }

    @Override
    public Flux<ForensicsEvent> stream() {
        return sink.asFlux();
    }
}
