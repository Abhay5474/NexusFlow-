package io.aetheros.control;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * REST controller for Project SHIELD-FABRIC — anti-tap monitoring.
 *
 * Endpoints:
 *   GET /api/shield/alerts — recent tap detection alerts
 */
@RestController
@RequestMapping("/api/shield")
@CrossOrigin(origins = "*")
public class ShieldFabricController {

    @GetMapping("/alerts")
    public ResponseEntity<List<Map<String, Object>>> getAlerts() {
        return ResponseEntity.ok(List.of(
            Map.of("ts",Instant.now().minusSeconds(120).toString(),"laneId",2,"rttDeltaMs",23.4,"pmtuChange",-28,"duplicateRatio",0.04,"action","CIPHER_ROTATE"),
            Map.of("ts",Instant.now().minusSeconds(840).toString(),"laneId",0,"rttDeltaMs",58.1,"pmtuChange",-76,"duplicateRatio",0.12,"action","INTERFACE_MIGRATE")
        ));
    }
}
