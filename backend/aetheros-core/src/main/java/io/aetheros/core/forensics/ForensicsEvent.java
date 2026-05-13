package io.aetheros.core.forensics;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable record describing a single routing/observability decision.
 *
 * <p>Emitted by every stage of the data plane. The control plane fans these
 * out to: Micrometer (bounded-cardinality counters), the WebSocket
 * forensics stream (full payload, drop-oldest backpressure), and structured
 * logs (sampled).
 *
 * <p>{@code connectionId} is intentionally not a Micrometer tag — it is a
 * WS/log field only. Anything that becomes a Prometheus label must have
 * bounded cardinality (see {@link Stage}, provider id, lane id).
 */
public record ForensicsEvent(
        Instant ts,
        String connectionId,
        Stage stage,
        String decision,
        Map<String, Object> tags
) {
    public enum Stage {
        SOCKS_HANDSHAKE,
        DNS,
        TLS_PEEK,
        LANE_PICK,
        RELAY,
        CLOSE
    }
}
