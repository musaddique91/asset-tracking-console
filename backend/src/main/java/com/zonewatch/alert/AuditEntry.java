package com.zonewatch.alert;

import java.time.Instant;

public record AuditEntry(Instant at, String actor, String action, String fromStatus, String toStatus) {
}
