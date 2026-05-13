package io.aetheros.control.api;

import io.aetheros.core.forensics.ForensicsEventPort;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

/**
 * WebSocket forensics stream. Each connected NOC dashboard receives the
 * multicast feed as JSON frames. Backpressure: drop-oldest (handled in
 * {@code ReactorForensicsBus}).
 */
@Component
public final class ForensicsStreamHandler implements WebSocketHandler {

    private final ForensicsEventPort bus;

    public ForensicsStreamHandler(ForensicsEventPort bus) {
        this.bus = bus;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        var frames = bus.stream()
                .map(ev -> session.textMessage(toJson(ev)));
        return session.send(frames);
    }

    private static String toJson(io.aetheros.core.forensics.ForensicsEvent ev) {
        // Minimal hand-rolled JSON to avoid pulling Jackson into the hot path;
        // a real impl uses ObjectMapper with a precomputed writer.
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"ts\":\"").append(ev.ts())
          .append("\",\"connectionId\":\"").append(ev.connectionId())
          .append("\",\"stage\":\"").append(ev.stage())
          .append("\",\"decision\":\"").append(escape(ev.decision()))
          .append("\",\"tags\":").append(ev.tags()).append('}');
        return sb.toString();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
