package io.aetheros.control;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * REST controller for Project COUNTER-SCORE — PID-based flow analytics.
 *
 * Endpoints:
 *   GET /api/counterscore/flows — per-process flow metrics
 */
@RestController
@RequestMapping("/api/counterscore")
@CrossOrigin(origins = "*")
public class CounterScoreController {

    private final AtomicLong tick = new AtomicLong(0);

    @GetMapping("/flows")
    public ResponseEntity<List<Map<String, Object>>> getFlows() {
        long t = tick.incrementAndGet();
        return ResponseEntity.ok(List.of(
            Map.of("pid",1234,"processName","chrome.exe","bytesIn",45_280_000L+(t*10000),"bytesOut",8_420_000L+(t*1000),"blockedRequests",342+(t%10),"activeConnections",48,"threatScore",0.12,"protocols",List.of("HTTPS","QUIC→HTTP2")),
            Map.of("pid",5678,"processName","firefox.exe","bytesIn",12_800_000L+(t*5000),"bytesOut",2_100_000L,"blockedRequests",87,"activeConnections",12,"threatScore",0.08,"protocols",List.of("HTTPS")),
            Map.of("pid",9012,"processName","discord.exe","bytesIn",8_400_000L,"bytesOut",4_200_000L+(t*2000),"blockedRequests",24,"activeConnections",8,"threatScore",0.15,"protocols",List.of("WSS","HTTPS")),
            Map.of("pid",3456,"processName","WindowsUpdate.exe","bytesIn",120_000_000L+(t*50000),"bytesOut",480_000L,"blockedRequests",12,"activeConnections",4,"threatScore",0.22,"protocols",List.of("HTTPS")),
            Map.of("pid",2345,"processName","suspicious_bg.exe","bytesIn",48_000L,"bytesOut",2_100_000L+(t*500),"blockedRequests",0,"activeConnections",1,"threatScore",0.91,"protocols",List.of("HTTPS")),
            Map.of("pid",6789,"processName","vscode.exe","bytesIn",5_200_000L+(t*1000),"bytesOut",1_800_000L,"blockedRequests",15,"activeConnections",6,"threatScore",0.05,"protocols",List.of("HTTPS","WS"))
        ));
    }
}
