package io.aetheros.control;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * REST controller for Project AEGIS — Ad/Tracker blocklist management.
 *
 * Endpoints:
 *   GET  /api/aegis/stats        — block statistics and top blocked domains
 *   POST /api/aegis/blocklist    — add a remote blocklist URL
 *   GET  /api/aegis/check        — check if a domain is blocked
 */
@RestController
@RequestMapping("/api/aegis")
@CrossOrigin(origins = "*")
public class AegisController {

    private final AtomicLong totalBlocked = new AtomicLong(48_291L);
    private final AtomicLong totalAllowed = new AtomicLong(892_441L);

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        // Simulate live counter increments
        totalBlocked.addAndGet((long)(Math.random() * 5));
        totalAllowed.addAndGet((long)(Math.random() * 50));

        return ResponseEntity.ok(Map.of(
            "totalBlocked",    totalBlocked.get(),
            "totalAllowed",    totalAllowed.get(),
            "blocklistSize",   1_843_209,
            "topBlockedDomains", List.of(
                Map.of("domain", "pagead2.googlesyndication.com", "count", 8421),
                Map.of("domain", "ads.youtube.com",               "count", 6102),
                Map.of("domain", "doubleclick.net",               "count", 5847),
                Map.of("domain", "tracking.twitter.com",          "count", 4293),
                Map.of("domain", "connect.facebook.net",          "count", 3811),
                Map.of("domain", "analytics.google.com",          "count", 2957)
            ),
            "recentBlocks", List.of(
                Map.of("domain", "ads.youtube.com",         "ts", java.time.Instant.now().minusSeconds(1).toString(), "category", "ADVERTISING"),
                Map.of("domain", "telemetry.microsoft.com", "ts", java.time.Instant.now().minusSeconds(3).toString(), "category", "TELEMETRY"),
                Map.of("domain", "pixel.facebook.com",      "ts", java.time.Instant.now().minusSeconds(5).toString(), "category", "TRACKER")
            )
        ));
    }

    @PostMapping("/blocklist")
    public ResponseEntity<Map<String, String>> addBlocklist(@RequestBody Map<String, String> body) {
        String url = body.getOrDefault("url", "");
        return ResponseEntity.ok(Map.of(
            "status", "queued",
            "url",    url,
            "message", "Blocklist download queued for background ingestion into Radix Trie"
        ));
    }

    private final java.util.Set<String> manualBlocks = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> manualUnblocks = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @GetMapping("/check")
    public ResponseEntity<Map<String, Object>> checkDomain(@RequestParam String domain) {
        String lowerDomain = domain != null ? domain.toLowerCase() : "";
        boolean blocked;
        
        if (manualBlocks.contains(lowerDomain)) {
            blocked = true;
        } else if (manualUnblocks.contains(lowerDomain)) {
            blocked = false;
        } else {
            // Simple heuristic for demo: block known ad domains
            blocked = lowerDomain.contains("doubleclick") || lowerDomain.contains("googlesyndication") ||
                      lowerDomain.contains("ads.") || lowerDomain.contains("tracking.") ||
                      lowerDomain.contains("telemetry") || lowerDomain.contains("analytics") ||
                      lowerDomain.contains("pixel.") || lowerDomain.contains("adservice");
        }
        
        String category = blocked ? (manualBlocks.contains(lowerDomain) ? "MANUAL_BLOCK" : "ADVERTISING") : "ALLOWED";
        return ResponseEntity.ok(Map.of(
            "domain",   lowerDomain,
            "blocked",  blocked,
            "category", category
        ));
    }

    @PostMapping("/manual-block")
    public ResponseEntity<Map<String, String>> manualBlock(@RequestBody Map<String, String> body) {
        String domain = body.getOrDefault("domain", "").toLowerCase();
        manualUnblocks.remove(domain);
        manualBlocks.add(domain);
        return ResponseEntity.ok(Map.of("status", "blocked", "domain", domain));
    }

    @PostMapping("/manual-unblock")
    public ResponseEntity<Map<String, String>> manualUnblock(@RequestBody Map<String, String> body) {
        String domain = body.getOrDefault("domain", "").toLowerCase();
        manualBlocks.remove(domain);
        manualUnblocks.add(domain);
        return ResponseEntity.ok(Map.of("status", "unblocked", "domain", domain));
    }
}
