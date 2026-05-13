package io.aetheros.control.api;

import io.aetheros.control.config.AetherProperties;
import io.aetheros.nexus.LaneManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/diagnostics")
public class DiagnosticsController {

    private final AetherProperties props;
    private final LaneManager lanes;

    public DiagnosticsController(AetherProperties props, LaneManager lanes) {
        this.props = props;
        this.lanes = lanes;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "proxy", Map.of(
                        "host", props.getProxy().getBindHost(),
                        "port", props.getProxy().getBindPort()),
                "lanes", Map.of(
                        "count", lanes.snapshot().size(),
                        "strategy", lanes.currentStrategy()));
    }
}
