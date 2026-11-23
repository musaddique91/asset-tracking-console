package com.zonewatch.asset;

import java.time.Instant;

public record TrackPoint(String reportId, Instant observedAt, double lat, double lon, String source) {
}
