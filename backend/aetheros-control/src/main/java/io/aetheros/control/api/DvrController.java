package io.aetheros.control.api;

import io.aetheros.control.dvr.ForensicsRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/dvr")
public class DvrController {

    private final ForensicsRepository repo;

    public DvrController(ForensicsRepository repo) { this.repo = repo; }

    /**
     * Query events in a time window. Defaults: last 5 minutes.
     * Hard-capped at 5,000 events to protect the dashboard.
     */
    @GetMapping("/events")
    public List<Map<String, Object>> events(
            @RequestParam(required = false) Long fromMs,
            @RequestParam(required = false) Long toMs,
            @RequestParam(defaultValue = "5000") int limit) {
        Instant to   = (toMs   != null) ? Instant.ofEpochMilli(toMs)   : Instant.now();
        Instant from = (fromMs != null) ? Instant.ofEpochMilli(fromMs) : to.minusSeconds(300);
        return repo.findWindow(from, to, PageRequest.of(0, Math.min(limit, 5_000)))
                .stream()
                .map(r -> Map.<String, Object>of(
                        "ts", r.getTs().toEpochMilli(),
                        "connectionId", r.getConnectionId(),
                        "stage", r.getStage(),
                        "decision", r.getDecision(),
                        "tags", r.getTagsJson()))
                .toList();
    }

    @GetMapping("/range")
    public Map<String, Object> range() {
        long count = repo.count();
        return Map.of("count", count, "nowMs", System.currentTimeMillis());
    }
}
