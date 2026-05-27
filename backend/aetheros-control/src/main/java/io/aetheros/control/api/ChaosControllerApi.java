package io.aetheros.control.api;

import io.aetheros.nexus.LaneManager;
import io.aetheros.nexus.chaos.ChaosController;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/chaos")
public class ChaosControllerApi {

    private final ChaosController chaos;
    private final LaneManager lanes;

    public ChaosControllerApi(ChaosController chaos, LaneManager lanes) {
        this.chaos = chaos;
        this.lanes = lanes;
    }

    @GetMapping
    public Map<String, Object> status() {
        var p = chaos.get();
        return Map.of(
                "enabled", p.enabled(),
                "laneKillProbability", p.laneKillProbability(),
                "injectedLatencyMs", p.injectedLatency().toMillis(),
                "dnsDropProbability", p.dnsDropProbability());
    }

    /** POST {enabled, laneKillProbability, injectedLatencyMs, dnsDropProbability} */
    @PostMapping
    public Map<String, Object> set(@RequestBody Map<String, Object> body) {
        boolean enabled = (boolean) body.getOrDefault("enabled", false);
        double kill   = num(body.get("laneKillProbability"), 0.0);
        long latMs    = (long) num(body.get("injectedLatencyMs"), 0.0);
        double drop   = num(body.get("dnsDropProbability"), 0.0);
        chaos.set(new ChaosController.Profile(enabled, kill, Duration.ofMillis(latMs), drop));
        return status();
    }

    @PostMapping("/lanes/{id}/kill")
    public Map<String, Object> killLane(@PathVariable("id") int id) {
        lanes.forceHealth(id, false);
        return Map.of("laneId", id, "healthy", false);
    }

    @PostMapping("/lanes/{id}/revive")
    public Map<String, Object> reviveLane(@PathVariable("id") int id) {
        lanes.forceHealth(id, true);
        return Map.of("laneId", id, "healthy", true);
    }

    @DeleteMapping
    public Map<String, Object> disable() { chaos.disable(); return status(); }

    private static double num(Object o, double dflt) {
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(o)); }
        catch (Exception e) { return dflt; }
    }
}
