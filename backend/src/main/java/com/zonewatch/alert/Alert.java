package com.zonewatch.alert;

import java.time.Instant;

public record Alert(
        String alertId,
        String assetId,
        String zoneId,
        AlertStatus status,
        Instant openedAt,
        String triggerReportId,
        Instant triggerObservedAt,
        String claimedBy,
        Instant claimedAt,
        String resolvedBy,
        Instant resolvedAt,
        String resolutionNote) {
}
