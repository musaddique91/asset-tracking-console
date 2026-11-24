package com.zonewatch.asset;

import java.time.Instant;

/** sourceType: added by CR-1 (v1.1.0). */
public record TrackPoint(String reportId, Instant observedAt, double lat, double lon, String source, String sourceType) {
}
