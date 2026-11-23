package com.zonewatch.zone;

import com.fasterxml.jackson.databind.JsonNode;

/** A zone as loaded. geometry is the original GeoJSON geometry, kept for display. */
public record Zone(String zoneId, String name, String zoneClass, ZoneShape shape, JsonNode geometry) {

    public boolean restricted() {
        return "RESTRICTED".equals(zoneClass);
    }
}
