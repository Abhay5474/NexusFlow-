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

    @GetMapping("/check")
    public ResponseEntity<Map<String, Object>> checkDomain(@RequestParam String domain) {
        // Simple heuristic for demo: block known ad domains
        boolean blocked = domain != null && (
            domain.contains("doubleclick") || domain.contains("googlesyndication") ||
            domain.contains("ads.") || domain.contains("tracking.") ||
            domain.contains("telemetry") || domain.contains("analytics") ||
            domain.contains("pixel.") || domain.contains("adservice")
        );
        String category = blocked ? "ADVERTISING" : "ALLOWED";
        return ResponseEntity.ok(Map.of(
            "domain",   domain,
            "blocked",  blocked,
            "category", category
        ));
    }
}
