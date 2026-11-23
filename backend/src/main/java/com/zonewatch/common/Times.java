package com.zonewatch.common;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** API time format: ISO-8601, UTC, milliseconds, trailing Z. */
public final class Times {

    public static final DateTimeFormatter API =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private Times() {
    }

    public static String format(Instant instant) {
        return instant == null ? null : API.format(instant);
    }

    public static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    public static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
