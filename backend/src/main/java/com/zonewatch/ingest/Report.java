package com.zonewatch.ingest;

import java.time.Instant;
import java.util.Objects;

/** A valid report. line is the 1-based position among the non-empty lines of its request. */
public record Report(
        int line,
        String reportId,
        String assetId,
        String kind,
        Instant observedAt,
        double lat,
        double lon,
        Double speedKn,
        Double headingDeg,
        String source,
        String raw) {

    /** Event-time order of reports of one asset: observedAt, then reportId as a deterministic tie-break. */
    public int compareKey(Instant otherObservedAt, String otherReportId) {
        int c = observedAt.compareTo(otherObservedAt);
        return c != 0 ? c : reportId.compareTo(otherReportId);
    }

    public boolean sameContent(String assetId, Instant observedAt, double lat, double lon,
                               Double speedKn, Double headingDeg, String source, String kind) {
        return this.assetId.equals(assetId) && this.observedAt.equals(observedAt)
                && Double.compare(this.lat, lat) == 0 && Double.compare(this.lon, lon) == 0
                && Objects.equals(this.speedKn, speedKn) && Objects.equals(this.headingDeg, headingDeg)
                && Objects.equals(this.source, source) && Objects.equals(this.kind, kind);
    }
}
