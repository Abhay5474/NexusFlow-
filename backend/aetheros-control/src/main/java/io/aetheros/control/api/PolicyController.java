package io.aetheros.control.api;

import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Minimal in-memory routing-policy surface. The deny list is enforced at
 * the SOCKS REQUEST phase by the proxy module's policy hook (planned wire
 * point — exposed here for the dashboard).
 *
 * <p>Hard-coded baseline includes anti-abuse rules per
 * {@code ARCHITECTURE.md §8} (e.g. SMTP on 25 to arbitrary upstreams).
 */
@RestController
@RequestMapping("/api/policy")
public class PolicyController {

    private final Set<String> denyHostSuffixes = new CopyOnWriteArraySet<>();
    private final Set<Integer> denyPorts = new CopyOnWriteArraySet<>(Set.of(25));

    @GetMapping
    public Map<String, Object> snapshot() {
        return Map.of(
                "denyHostSuffixes", denyHostSuffixes,
                "denyPorts", denyPorts);
    }

    @PostMapping("/deny-host")
    public Map<String, Object> addDenyHost(@RequestBody Map<String, String> body) {
        String s = body.getOrDefault("suffix", "").trim().toLowerCase();
        if (!s.isEmpty()) denyHostSuffixes.add(s);
        return snapshot();
    }

    @DeleteMapping("/deny-host")
    public Map<String, Object> removeDenyHost(@RequestParam String suffix) {
        denyHostSuffixes.remove(suffix.toLowerCase());
        return snapshot();
    }

    @PostMapping("/deny-port")
    public Map<String, Object> addDenyPort(@RequestBody Map<String, Integer> body) {
        Integer p = body.get("port");
        if (p != null) denyPorts.add(p);
        return snapshot();
    }
}
