package io.aetheros.control.api;

import io.aetheros.bandshifter.ClassDistribution;
import io.aetheros.bandshifter.TrafficClass;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/bandshifter")
public class DistributionController {

    private final ClassDistribution dist;

    public DistributionController(ClassDistribution dist) { this.dist = dist; }

    @GetMapping("/distribution")
    public Map<String, Object> snapshot() {
        var bytes = dist.bytesSnapshot();
        var flows = dist.flowsSnapshot();
        List<Map<String, Object>> classes = new ArrayList<>();
        for (TrafficClass c : TrafficClass.values()) {
            classes.add(Map.of(
                    "class", c.name(),
                    "bytes", bytes.getOrDefault(c, 0L),
                    "flows", flows.getOrDefault(c, 0L)));
        }
        return Map.of("classes", classes);
    }
}
