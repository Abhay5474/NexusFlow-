package io.aetheros.control;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * REST controller for Project IRONCLAD — TUN adapter management.
 *
 * Endpoints:
 *   GET  /api/ironclad/status  — adapter status, packet counters
 *   POST /api/ironclad/start   — activate the TUN adapter
 *   POST /api/ironclad/stop    — deactivate the TUN adapter
 */
@RestController
@RequestMapping("/api/ironclad")
@CrossOrigin(origins = "*")
public class IroncladController {

    /** Simulated adapter state (in production, delegates to IroncladAdapter bean). */
    private final AtomicBoolean active         = new AtomicBoolean(true);
    private final AtomicLong    packetsIngested = new AtomicLong(4_821_093L);
    private final AtomicLong    bytesIngested   = new AtomicLong(7_340_032_000L);

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        // Simulate live counter increments
        packetsIngested.addAndGet(active.get() ? (long)(Math.random() * 1000) : 0);
        bytesIngested.addAndGet(active.get() ? (long)(Math.random() * 1_500_000) : 0);

        return ResponseEntity.ok(Map.of(
            "adapterName",            "NexusFlow",
            "active",                 active.get(),
            "packetsIngested",        packetsIngested.get(),
            "bytesIngested",          bytesIngested.get(),
            "routingTableModified",   active.get(),
            "virtualThreadPoolSize",  64
        ));
    }

    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> start() {
        active.set(true);
        return getStatus();
    }

    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stop() {
        active.set(false);
        return getStatus();
    }
}
