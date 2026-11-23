package com.zonewatch.asset;

import java.time.Instant;

public record AssetPosition(String assetId, String kind, String reportId, Instant observedAt, double lat, double lon,
                            Double speedKn, Double headingDeg, String source) {
}
