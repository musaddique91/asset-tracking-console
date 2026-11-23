package com.zonewatch.zone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zonewatch.config.ZwProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * The zones, loaded once from zones.geojson (bundled, or ZW_ZONES_FILE). Zones are reference data;
 * every instance evaluates containment in memory, so no lock or cache is shared between instances.
 * Loading fails fast on anything it cannot interpret instead of silently dropping a zone.
 */
@Component
public class ZoneRegistry {

    private static final Logger log = LoggerFactory.getLogger(ZoneRegistry.class);

    private final Map<String, Zone> zones;
    private final List<Zone> restricted;
    private final String rawGeoJson;

    public ZoneRegistry(ZwProperties props, ObjectMapper mapper) throws IOException {
        byte[] bytes;
        if (props.zonesFile() != null && !props.zonesFile().isBlank()) {
            bytes = Files.readAllBytes(Path.of(props.zonesFile()));
        } else {
            try (InputStream in = new ClassPathResource("zones.geojson").getInputStream()) {
                bytes = in.readAllBytes();
            }
        }
        this.rawGeoJson = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        this.zones = Collections.unmodifiableMap(parse(mapper.readTree(bytes)));
        this.restricted = zones.values().stream().filter(Zone::restricted).toList();
        log.info("loaded {} zones ({} restricted)", zones.size(), restricted.size());
    }

    public Map<String, Zone> all() {
        return zones;
    }

    public List<Zone> restricted() {
        return restricted;
    }

    String rawGeoJson() {
        return rawGeoJson;
    }

    /** Restricted zones that contain the point. */
    public Set<String> restrictedZonesAt(double lon, double lat) {
        Set<String> inside = null;
        for (Zone z : restricted) {
            if (z.shape().contains(lon, lat)) {
                if (inside == null) {
                    inside = new HashSet<>(2);
                }
                inside.add(z.zoneId());
            }
        }
        return inside == null ? Set.of() : inside;
    }

    static Map<String, Zone> parse(JsonNode root) {
        if (!"FeatureCollection".equals(root.path("type").asText())) {
            throw new IllegalStateException("zones file is not a GeoJSON FeatureCollection");
        }
        Map<String, Zone> result = new LinkedHashMap<>();
        for (JsonNode feature : root.path("features")) {
            JsonNode props = feature.path("properties");
            String id = props.path("zoneId").asText(null);
            String name = props.path("name").asText(id);
            String zoneClass = props.path("class").asText("");
            if (id == null || id.isBlank()) {
                throw new IllegalStateException("zone without zoneId");
            }
            if (!zoneClass.equals("RESTRICTED") && !zoneClass.equals("ADVISORY")) {
                throw new IllegalStateException("zone " + id + " has unknown class '" + zoneClass + "'");
            }
            JsonNode geometry = feature.path("geometry");
            ZoneShape shape = ZoneShape.of(polygons(id, geometry));
            if (shape.crossesAntimeridian()) {
                log.warn("zone {} crosses the 180 degree meridian; unwrapped for containment", id);
            }
            if (result.put(id, new Zone(id, name, zoneClass, shape, geometry)) != null) {
                throw new IllegalStateException("duplicate zoneId " + id);
            }
        }
        return result;
    }

    private static List<List<List<double[]>>> polygons(String id, JsonNode geometry) {
        String type = geometry.path("type").asText();
        JsonNode coords = geometry.path("coordinates");
        List<List<List<double[]>>> result = new ArrayList<>();
        switch (type) {
            case "Polygon" -> result.add(rings(id, coords));
            case "MultiPolygon" -> coords.forEach(p -> result.add(rings(id, p)));
            default -> throw new IllegalStateException("zone " + id + " has unsupported geometry '" + type + "'");
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("zone " + id + " has no polygon");
        }
        return result;
    }

    private static List<List<double[]>> rings(String id, JsonNode polygon) {
        List<List<double[]>> rings = new ArrayList<>();
        for (JsonNode ring : polygon) {
            List<double[]> points = new ArrayList<>();
            for (JsonNode pos : ring) {
                double lon = pos.path(0).asDouble(Double.NaN);
                double lat = pos.path(1).asDouble(Double.NaN);
                if (Double.isNaN(lon) || Double.isNaN(lat) || Math.abs(lat) > 90 || Math.abs(lon) > 180) {
                    throw new IllegalStateException("zone " + id + " has an invalid position");
                }
                points.add(new double[] {lon, lat});
            }
            if (points.size() < 3) {
                throw new IllegalStateException("zone " + id + " has a ring with fewer than 3 positions");
            }
            rings.add(points);
        }
        if (rings.isEmpty()) {
            throw new IllegalStateException("zone " + id + " has a polygon without rings");
        }
        return rings;
    }
}
