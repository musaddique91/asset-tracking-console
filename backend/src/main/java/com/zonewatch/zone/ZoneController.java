package com.zonewatch.zone;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Not part of the fixed contract: the console needs zone shapes and a non-map occupancy view. */
@RestController
@RequestMapping("/api/v1/zones")
public class ZoneController {

    private final ZoneService zones;

    public ZoneController(ZoneService zones) {
        this.zones = zones;
    }

    @GetMapping
    public Map<String, List<Map<String, Object>>> zones() {
        return Map.of("items", zones.zones());
    }

    @GetMapping("/occupancy")
    public Map<String, List<ZoneService.Occupancy>> occupancy() {
        return Map.of("items", zones.occupancy());
    }
}
