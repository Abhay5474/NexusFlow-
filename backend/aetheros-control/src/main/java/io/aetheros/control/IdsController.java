package io.aetheros.control;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * REST controller for Project SENTINEL-IDS — alert and rule management.
 *
 * Endpoints:
 *   GET  /api/ids/alerts  — recent IDS/IPS alert feed
 *   GET  /api/ids/rules   — active ruleset
 *   POST /api/ids/rules   — add a new rule
 */
@RestController
@RequestMapping("/api/ids")
@CrossOrigin(origins = "*")
public class IdsController {

    private static final List<Map<String, Object>> STATIC_ALERTS = List.of(
        Map.of("id","1","ts",Instant.now().minusSeconds(2).toString(),"ruleId","ET-2019401","severity","CRITICAL","payload","\\x00\\x00\\x00\\x00shellcode_nop_sled","action","DROPPED","srcIp","185.220.101.47","dstPort",443),
        Map.of("id","2","ts",Instant.now().minusSeconds(8).toString(),"ruleId","ET-2001219","severity","HIGH","payload","UNION SELECT NULL,NULL,NULL--","action","DROPPED","srcIp","45.142.212.100","dstPort",80),
        Map.of("id","3","ts",Instant.now().minusSeconds(15).toString(),"ruleId","ET-2018752","severity","MEDIUM","payload","X-Forwarded-For: 127.0.0.1","action","LOGGED","srcIp","104.21.55.12","dstPort",8080),
        Map.of("id","4","ts",Instant.now().minusSeconds(23).toString(),"ruleId","ET-2001045","severity","LOW","payload","User-Agent: zgrab/0.x","action","LOGGED","srcIp","209.141.36.20","dstPort",443),
        Map.of("id","5","ts",Instant.now().minusSeconds(45).toString(),"ruleId","ET-2019855","severity","HIGH","payload","../../../etc/passwd","action","DROPPED","srcIp","193.239.84.200","dstPort",80)
    );

    private final CopyOnWriteArrayList<Map<String, Object>> customRules = new CopyOnWriteArrayList<>();

    @GetMapping("/alerts")
    public ResponseEntity<List<Map<String, Object>>> getAlerts() {
        return ResponseEntity.ok(STATIC_ALERTS);
    }

    @GetMapping("/rules")
    public ResponseEntity<List<Map<String, Object>>> getRules() {
        var rules = new ArrayList<>(List.of(
            Map.of("ruleId","ET-2019401","description","Shellcode NOP sled pattern","severity","CRITICAL","action","DROP","dstPort",0),
            Map.of("ruleId","ET-2001219","description","SQL injection UNION SELECT","severity","HIGH","action","DROP","dstPort",80),
            Map.of("ruleId","ET-2019855","description","Directory traversal","severity","HIGH","action","DROP","dstPort",80),
            Map.of("ruleId","ET-2018752","description","X-Forwarded-For localhost injection","severity","MEDIUM","action","LOG","dstPort",0),
            Map.of("ruleId","ET-2034647","description","Log4Shell JNDI injection","severity","CRITICAL","action","DROP","dstPort",0)
        ));
        rules.addAll(customRules);
        return ResponseEntity.ok(rules);
    }

    @PostMapping("/rules")
    public ResponseEntity<Map<String, String>> addRule(@RequestBody Map<String, Object> rule) {
        customRules.add(rule);
        return ResponseEntity.ok(Map.of("status", "added", "ruleId", rule.getOrDefault("ruleId","custom").toString()));
    }
}
