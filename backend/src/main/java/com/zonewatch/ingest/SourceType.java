package com.zonewatch.ingest;

/**
 * CR-1: the kind of source a report came from, derived from its source field by prefix.
 * Must give the same answer as the SQL function report_source_type (V2 migration); a test checks both.
 */
public enum SourceType {
    SATELLITE, GROUND, MOBILE, UNKNOWN;

    public static SourceType of(String source) {
        if (source == null) {
            return UNKNOWN;
        }
        if (source.startsWith("SAT")) {
            return SATELLITE;
        }
        if (source.startsWith("GND")) {
            return GROUND;
        }
        if (source.startsWith("MOB")) {
            return MOBILE;
        }
        return UNKNOWN;
    }
}
