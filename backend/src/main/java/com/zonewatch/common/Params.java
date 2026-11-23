package com.zonewatch.common;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Parsing of query parameters with contract errors (400 VALIDATION_FAILED). */
public final class Params {

    private Params() {
    }

    public static int limit(String raw, int defaultValue, int max) {
        if (raw == null) {
            return defaultValue;
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw ApiException.validation("limit must be an integer from 1 to " + max);
        }
        if (value < 1 || value > max) {
            throw ApiException.validation("limit must be an integer from 1 to " + max);
        }
        return value;
    }

    public static Instant instant(String name, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw.trim(), DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            throw ApiException.validation(name + " must be an ISO-8601 date-time with zone");
        }
    }
}
