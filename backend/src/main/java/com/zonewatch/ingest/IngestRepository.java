package com.zonewatch.ingest;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Set-based SQL for one ingest batch. Every statement takes arrays and handles the whole batch in
 * one round trip (unnest), which is what makes 2,000+ lines per second possible.
 * Must be called inside the batch transaction.
 */
@Repository
public class IngestRepository {

    private static final int MAX_RAW = 8192;

    private final JdbcTemplate jdbc;

    public IngestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** R1: inserts the reports whose reportId is new; returns the reportIds that were really inserted. */
    public Set<String> insertReports(List<Report> reports) {
        if (reports.isEmpty()) {
            return Set.of();
        }
        return jdbc.execute((ConnectionCallback<Set<String>>) c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO report (report_id, asset_id, kind, observed_at, lat, lon, speed_kn, heading_deg, source, source_type)
                    SELECT r, a, k, o::timestamptz, la, lo, s, h, src, st
                    FROM unnest(?::text[], ?::text[], ?::text[], ?::text[], ?::float8[], ?::float8[], ?::float8[], ?::float8[], ?::text[], ?::text[])
                         AS u(r, a, k, o, la, lo, s, h, src, st)
                    ON CONFLICT (report_id) DO NOTHING
                    RETURNING report_id
                    """)) {
                bindReports(c, ps, reports);
                // CR-1: v1.1.0 writes the source type itself; the V2 trigger fills it only for v1.0.0 writers
                ps.setArray(10, c.createArrayOf("text", reports.stream().map(r -> SourceType.of(r.source()).name()).toArray()));
                Set<String> inserted = new HashSet<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        inserted.add(rs.getString(1));
                    }
                }
                return inserted;
            }
        });
    }

    /** Stored versions of reports that were delivered again, for conflict detection. */
    public Map<String, Report> storedReports(Collection<String> reportIds) {
        if (reportIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.execute((ConnectionCallback<Map<String, Report>>) c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT report_id, asset_id, kind, observed_at, lat, lon, speed_kn, heading_deg, source
                    FROM report WHERE report_id = ANY(?)
                    """)) {
                ps.setArray(1, c.createArrayOf("text", reportIds.toArray()));
                Map<String, Report> out = new HashMap<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(rs.getString(1), new Report(0, rs.getString(1), rs.getString(2), rs.getString(3),
                                rs.getTimestamp(4).toInstant(), rs.getDouble(5), rs.getDouble(6),
                                (Double) rs.getObject(7), (Double) rs.getObject(8), rs.getString(9), null));
                    }
                }
                return out;
            }
        });
    }

    /**
     * Creates the position row of assets seen for the first time, using their oldest accepted report.
     * Returns the assets that were created by this call. Sorted input keeps lock order stable.
     */
    public Set<String> insertNewAssets(List<Report> firstReports) {
        if (firstReports.isEmpty()) {
            return Set.of();
        }
        return jdbc.execute((ConnectionCallback<Set<String>>) c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO asset_position (report_id, asset_id, kind, observed_at, lat, lon, speed_kn, heading_deg, source)
                    SELECT r, a, k, o::timestamptz, la, lo, s, h, src
                    FROM unnest(?::text[], ?::text[], ?::text[], ?::text[], ?::float8[], ?::float8[], ?::float8[], ?::float8[], ?::text[])
                         WITH ORDINALITY AS u(r, a, k, o, la, lo, s, h, src, ord)
                    ORDER BY ord
                    ON CONFLICT (asset_id) DO NOTHING
                    RETURNING asset_id
                    """)) {
                bindReports(c, ps, firstReports);
                Set<String> created = new HashSet<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        created.add(rs.getString(1));
                    }
                }
                return created;
            }
        });
    }

    public record Current(String assetId, String reportId, Instant observedAt, double lat, double lon) {
    }

    /** R2: locks the position rows of the batch's assets, in asset order (no deadlock between batches). */
    public Map<String, Current> lockPositions(List<String> sortedAssetIds) {
        return jdbc.execute((ConnectionCallback<Map<String, Current>>) c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT asset_id, report_id, observed_at, lat, lon FROM asset_position
                    WHERE asset_id = ANY(?) ORDER BY asset_id FOR UPDATE
                    """)) {
                ps.setArray(1, c.createArrayOf("text", sortedAssetIds.toArray()));
                Map<String, Current> out = new HashMap<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(rs.getString(1), new Current(rs.getString(1), rs.getString(2),
                                rs.getTimestamp(3).toInstant(), rs.getDouble(4), rs.getDouble(5)));
                    }
                }
                return out;
            }
        });
    }

    /** Moves the current position of each asset to the given report. */
    public void updatePositions(List<Report> newest) {
        if (newest.isEmpty()) {
            return;
        }
        jdbc.execute((ConnectionCallback<Integer>) c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE asset_position p
                    SET report_id = u.r, kind = u.k, observed_at = u.o::timestamptz, lat = u.la, lon = u.lo,
                        speed_kn = u.s, heading_deg = u.h, source = u.src,
                        version = p.version + 1, changed_at = clock_timestamp()
                    FROM unnest(?::text[], ?::text[], ?::text[], ?::text[], ?::float8[], ?::float8[], ?::float8[], ?::float8[], ?::text[])
                         AS u(r, a, k, o, la, lo, s, h, src)
                    WHERE p.asset_id = u.a
                    """)) {
                bindReports(c, ps, newest);
                return ps.executeUpdate();
            }
        });
    }

    public record AlertCandidate(String alertId, String assetId, String zoneId, String reportId, Instant observedAt) {
    }

    /**
     * R3 + R5: opens an alert for each candidate unless an OPEN or CLAIMED alert exists for that asset
     * and zone (partial unique index), and writes the OPENED audit entry in the same statement.
     * Returns the ids of the alerts that were opened.
     */
    public List<String> openAlerts(List<AlertCandidate> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        return jdbc.execute((ConnectionCallback<List<String>>) c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    WITH opened AS (
                        INSERT INTO alert (alert_id, asset_id, zone_id, status, opened_at, trigger_report_id, trigger_observed_at)
                        SELECT i, a, z, 'OPEN', now(), r, o::timestamptz
                        FROM unnest(?::text[], ?::text[], ?::text[], ?::text[], ?::text[]) WITH ORDINALITY AS u(i, a, z, r, o, ord)
                        ORDER BY ord
                        ON CONFLICT (asset_id, zone_id) WHERE status IN ('OPEN', 'CLAIMED') DO NOTHING
                        RETURNING alert_id, opened_at
                    )
                    INSERT INTO alert_audit (alert_id, at, actor, action, from_status, to_status)
                    SELECT alert_id, opened_at, 'system', 'OPENED', NULL, 'OPEN' FROM opened
                    RETURNING alert_id
                    """)) {
                int n = candidates.size();
                Object[] ids = new Object[n], assets = new Object[n], zones = new Object[n], reports = new Object[n], obs = new Object[n];
                for (int i = 0; i < n; i++) {
                    AlertCandidate a = candidates.get(i);
                    ids[i] = a.alertId();
                    assets[i] = a.assetId();
                    zones[i] = a.zoneId();
                    reports[i] = a.reportId();
                    obs[i] = a.observedAt().toString();
                }
                ps.setArray(1, c.createArrayOf("text", ids));
                ps.setArray(2, c.createArrayOf("text", assets));
                ps.setArray(3, c.createArrayOf("text", zones));
                ps.setArray(4, c.createArrayOf("text", reports));
                ps.setArray(5, c.createArrayOf("text", obs));
                List<String> opened = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        opened.add(rs.getString(1));
                    }
                }
                return opened;
            }
        });
    }

    /** R6: every rejected line is kept with its reason and time. */
    public void insertRejections(List<Rejection> rejections, String requestId) {
        if (rejections.isEmpty()) {
            return;
        }
        jdbc.execute((ConnectionCallback<Integer>) c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO rejected_line (request_id, line_no, code, detail, raw, raw_truncated)
                    SELECT ?, l, cd, d, r, t
                    FROM unnest(?::int[], ?::text[], ?::text[], ?::text[], ?::bool[]) AS u(l, cd, d, r, t)
                    """)) {
                int n = rejections.size();
                Object[] lines = new Object[n], codes = new Object[n], details = new Object[n], raws = new Object[n], cut = new Object[n];
                for (int i = 0; i < n; i++) {
                    Rejection r = rejections.get(i);
                    lines[i] = r.line();
                    codes[i] = r.code().name();
                    details[i] = r.detail();
                    String raw = sanitize(r.raw());
                    cut[i] = raw.length() > MAX_RAW;
                    raws[i] = raw.length() > MAX_RAW ? raw.substring(0, MAX_RAW) : raw;
                }
                ps.setString(1, requestId);
                ps.setArray(2, c.createArrayOf("int4", lines));
                ps.setArray(3, c.createArrayOf("text", codes));
                ps.setArray(4, c.createArrayOf("text", details));
                ps.setArray(5, c.createArrayOf("text", raws));
                ps.setArray(6, c.createArrayOf("bool", cut));
                return ps.executeUpdate();
            }
        });
    }

    public void insertConflicts(List<Report> conflicting, String requestId) {
        if (conflicting.isEmpty()) {
            return;
        }
        jdbc.execute((ConnectionCallback<Integer>) c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO report_conflict (request_id, report_id, raw)
                    SELECT ?, r, w FROM unnest(?::text[], ?::text[]) AS u(r, w)
                    """)) {
                ps.setString(1, requestId);
                ps.setArray(2, c.createArrayOf("text", conflicting.stream().map(Report::reportId).toArray()));
                ps.setArray(3, c.createArrayOf("text", conflicting.stream().map(r -> sanitize(r.raw())).toArray()));
                return ps.executeUpdate();
            }
        });
    }

    public void recordBatch(String instance, String requestId, int received, int accepted, int duplicates,
                            int rejected, int durationMs) {
        jdbc.update("""
                INSERT INTO ingest_batch (instance, request_id, received, accepted, duplicates, rejected, duration_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, instance, requestId, received, accepted, duplicates, rejected, durationMs);
    }

    private static void bindReports(Connection c, PreparedStatement ps, List<Report> reports) throws SQLException {
        int n = reports.size();
        Object[] r = new Object[n], a = new Object[n], k = new Object[n], o = new Object[n];
        Object[] la = new Object[n], lo = new Object[n], s = new Object[n], h = new Object[n], src = new Object[n];
        for (int i = 0; i < n; i++) {
            Report x = reports.get(i);
            r[i] = x.reportId();
            a[i] = x.assetId();
            k[i] = x.kind();
            o[i] = x.observedAt().toString();
            la[i] = x.lat();
            lo[i] = x.lon();
            s[i] = x.speedKn();
            h[i] = x.headingDeg();
            src[i] = x.source();
        }
        ps.setArray(1, arr(c, "text", r));
        ps.setArray(2, arr(c, "text", a));
        ps.setArray(3, arr(c, "text", k));
        ps.setArray(4, arr(c, "text", o));
        ps.setArray(5, arr(c, "float8", la));
        ps.setArray(6, arr(c, "float8", lo));
        ps.setArray(7, arr(c, "float8", s));
        ps.setArray(8, arr(c, "float8", h));
        ps.setArray(9, arr(c, "text", src));
    }

    private static Array arr(Connection c, String type, Object[] values) throws SQLException {
        return c.createArrayOf(type, values);
    }

    /** PostgreSQL text cannot hold NUL; keep everything else exactly as received. */
    private static String sanitize(String raw) {
        return raw == null ? "" : raw.replace('\u0000', '�');
    }
}
