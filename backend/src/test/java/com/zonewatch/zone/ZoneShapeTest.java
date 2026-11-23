package com.zonewatch.zone;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Containment against the supplied zones.geojson, including its problems. */
class ZoneShapeTest {

    static Map<String, Zone> zones;

    @BeforeAll
    static void load() throws Exception {
        zones = ZoneRegistry.parse(new ObjectMapper().readTree(Files.readAllBytes(Path.of("src/main/resources/zones.geojson"))));
    }

    boolean in(String zone, double lon, double lat) {
        return zones.get(zone).shape().contains(lon, lat);
    }

    @Test
    void simplePolygon() {
        assertThat(in("Z-01", 168.4, 38.3)).isTrue();
        assertThat(in("Z-01", 168.9, 38.3)).isFalse();
    }

    @Test
    void boundaryCountsAsInside() {
        assertThat(in("Z-01", 168.0, 38.3)).isTrue();
        assertThat(in("Z-01", 168.8, 38.6)).isTrue();
    }

    @Test
    void holeIsOutside() {
        assertThat(in("Z-03", 172.7, 39.2)).isTrue();
        assertThat(in("Z-03", 173.2, 39.6)).isFalse();
    }

    @Test
    void everyPartOfAMultiPolygonCounts() {
        assertThat(in("Z-06", 167.2, 33.2)).isTrue();
        assertThat(in("Z-06", 168.1, 33.5)).isTrue();
        assertThat(in("Z-06", 167.7, 33.4)).isFalse();
    }

    @Test
    void clockwiseRingWorks() {
        assertThat(in("Z-02", 171.0, 35.4)).isTrue();
        assertThat(in("Z-02", 170.4, 35.4)).isFalse();
    }

    @Test
    void concaveLShape() {
        assertThat(in("Z-07", 176.3, 36.8)).isTrue();
        assertThat(in("Z-07", 177.0, 36.8)).isFalse();
    }

    @Test
    void zoneCrossingTheAntimeridianIsUnwrapped() {
        assertThat(zones.get("Z-08").shape().crossesAntimeridian()).isTrue();
        assertThat(in("Z-08", 179.6, 34.5)).isTrue();
        assertThat(in("Z-08", -179.6, 34.5)).isTrue();
        assertThat(in("Z-08", 180.0, 34.5)).isTrue();
        // read naively the ring covers everything between -179.4 and 179.3: these must stay outside
        assertThat(in("Z-08", 170.0, 34.5)).isFalse();
        assertThat(in("Z-08", 0.0, 34.5)).isFalse();
        assertThat(in("Z-08", -179.0, 34.5)).isFalse();
    }

    @Test
    void negativeLongitudeZone() {
        assertThat(in("Z-12", -178.0, 37.4)).isTrue();
        assertThat(in("Z-12", 178.0, 37.4)).isFalse();
    }

    @Test
    void overlappingZonesBothContainTheOverlap() {
        assertThat(in("Z-04", 174.8, 32.6)).isTrue();
        assertThat(in("Z-05", 174.8, 32.6)).isTrue();
    }

    @Test
    void classesAreRead() {
        assertThat(zones).hasSize(12);
        assertThat(zones.get("Z-09").restricted()).isFalse();
        assertThat(zones.get("Z-10").restricted()).isFalse();
        assertThat(zones.values().stream().filter(Zone::restricted)).hasSize(10);
    }
}
