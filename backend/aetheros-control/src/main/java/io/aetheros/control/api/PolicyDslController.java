package io.aetheros.control.api;

import io.aetheros.control.dsl.PolicyDsl;
import io.aetheros.control.policy.PolicyHolder;
import io.aetheros.core.policy.RoutingContext;
import io.aetheros.core.policy.RoutingDecision;
import io.aetheros.core.policy.RoutingPolicy;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalTime;
import java.util.Map;

@RestController
@RequestMapping("/api/policy/dsl")
public class PolicyDslController {

    private final PolicyHolder holder;

    public PolicyDslController(PolicyHolder holder) { this.holder = holder; }

    @GetMapping
    public Map<String, Object> current() {
        return Map.of("version", holder.version(), "source", holder.source());
    }

    /** Body: { "source": "<JSON DSL>" } */
    @PutMapping
    public ResponseEntity<Map<String, Object>> install(@RequestBody Map<String, String> body) {
        String src = body.getOrDefault("source", "");
        try {
            RoutingPolicy next = PolicyDsl.compile(src);
            holder.replace(next, src);
            return ResponseEntity.ok(Map.of("version", holder.version(), "ok", true));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "ok", false, "error", e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    /** Dry-run: evaluate the current policy against a (domain, port) pair. */
    @GetMapping("/evaluate")
    public Map<String, Object> evaluate(@RequestParam String domain, @RequestParam int port) {
        RoutingDecision d = holder.current().evaluate(new RoutingContext(domain, port, LocalTime.now()));
        return Map.of("decision", d.getClass().getSimpleName(), "detail", String.valueOf(d));
    }
}
