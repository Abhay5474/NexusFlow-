package io.aetheros.control.api;

import io.aetheros.core.lane.Lane;
import io.aetheros.nexus.LaneManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/lanes")
public class LaneController {

    private final LaneManager lanes;

    public LaneController(LaneManager lanes) { this.lanes = lanes; }

    @GetMapping
    public Map<String, Object> list() {
        return Map.of(
                "strategy", lanes.currentStrategy(),
                "lanes", lanes.snapshot().stream().map(this::view).toList());
    }

    @PutMapping("/strategy")
    public ResponseEntity<Map<String, Object>> setStrategy(@RequestBody Map<String, String> body) {
        String s = body.getOrDefault("strategy", "");
        boolean ok = lanes.setStrategy(s);
        if (!ok) return ResponseEntity.badRequest().body(Map.of(
                "error", "unknown strategy",
                "allowed", List.of("round-robin", "least-latency", "weighted", "congestion-aware")));
        return ResponseEntity.ok(Map.of("strategy", lanes.currentStrategy()));
    }

    private Map<String, Object> view(Lane l) {
        return Map.of(
                "id", l.id(),
                "label", l.label(),
                "latencyMs", l.observedLatency().toMillis(),
                "errorRate", l.errorRate(),
                "healthy", l.healthy(),
                "score", l.healthScore());
    }
}
