package com.zonewatch.alert;

import com.zonewatch.common.Times;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AlertRepository {

    static final String COLUMNS = """
            alert_id, asset_id, zone_id, status, opened_at, trigger_report_id, trigger_observed_at,
            claimed_by, claimed_at, resolved_by, resolved_at, resolution_note""";

    private final JdbcClient jdbc;

    public AlertRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Alert> find(String alertId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM alert WHERE alert_id = :id")
                .param("id", alertId).query(AlertRepository::alert).optional();
    }

    /** R4: the row lock serialises every command on one alert, across both instances. */
    public Optional<Alert> lock(String alertId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM alert WHERE alert_id = :id FOR UPDATE")
                .param("id", alertId).query(AlertRepository::alert).optional();
    }

    public record Row(long seq, Alert alert) {
    }

    public List<Row> page(AlertStatus status, String assetId, String zoneId, long afterSeq, int fetch) {
        StringBuilder sql = new StringBuilder("SELECT alert_seq, " + COLUMNS + " FROM alert WHERE alert_seq > :after");
        if (status != null) {
            sql.append(" AND status = :status");
        }
        if (assetId != null) {
            sql.append(" AND asset_id = :asset");
        }
        if (zoneId != null) {
            sql.append(" AND zone_id = :zone");
        }
        sql.append(" ORDER BY alert_seq LIMIT :fetch");
        var spec = jdbc.sql(sql.toString()).param("after", afterSeq).param("fetch", fetch);
        if (status != null) {
            spec = spec.param("status", status.name());
        }
        if (assetId != null) {
            spec = spec.param("asset", assetId);
        }
        if (zoneId != null) {
            spec = spec.param("zone", zoneId);
        }
        return spec.query((rs, n) -> new Row(rs.getLong(1), alertAt(rs, 1))).list();
    }

    public List<AuditEntry> audit(String alertId) {
        // bounded: an alert has one OPENED entry and a few commands; cap protects against abuse
        return jdbc.sql("""
                        SELECT at, actor, action, from_status, to_status FROM alert_audit
                        WHERE alert_id = :id ORDER BY audit_seq LIMIT 10000
                        """)
                .param("id", alertId)
                .query((rs, n) -> new AuditEntry(Times.instant(rs.getTimestamp(1)), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5)))
                .list();
    }

    public Alert claim(String alertId, String user) {
        return jdbc.sql("""
                        UPDATE alert SET status = 'CLAIMED', claimed_by = :u, claimed_at = now(),
                            version = version + 1, changed_at = clock_timestamp()
                        WHERE alert_id = :id RETURNING""" + " " + COLUMNS)
                .param("u", user).param("id", alertId).query(AlertRepository::alert).single();
    }

    public Alert release(String alertId) {
        return jdbc.sql("""
                        UPDATE alert SET status = 'OPEN', claimed_by = NULL, claimed_at = NULL,
                            version = version + 1, changed_at = clock_timestamp()
                        WHERE alert_id = :id RETURNING""" + " " + COLUMNS)
                .param("id", alertId).query(AlertRepository::alert).single();
    }

    public Alert resolve(String alertId, String user, String note) {
        return jdbc.sql("""
                        UPDATE alert SET status = 'RESOLVED', resolved_by = :u, resolved_at = now(), resolution_note = :note,
                            version = version + 1, changed_at = clock_timestamp()
                        WHERE alert_id = :id RETURNING""" + " " + COLUMNS)
                .param("u", user).param("note", note).param("id", alertId).query(AlertRepository::alert).single();
    }

    /** R5: written in the same transaction as the change it records. */
    public void audit(String alertId, String actor, String action, AlertStatus from, AlertStatus to) {
        jdbc.sql("""
                        INSERT INTO alert_audit (alert_id, at, actor, action, from_status, to_status)
                        VALUES (:id, now(), :actor, :action, :from, :to)
                        """)
                .param("id", alertId).param("actor", actor).param("action", action)
                .param("from", from.name()).param("to", to.name()).update();
    }

    public Map<String, Long> countsByStatus() {
        Map<String, Long> counts = new java.util.HashMap<>(Map.of("OPEN", 0L, "CLAIMED", 0L, "RESOLVED", 0L));
        jdbc.sql("SELECT status, count(*) FROM alert GROUP BY status")
                .query((rs, n) -> Map.entry(rs.getString(1), rs.getLong(2))).list()
                .forEach(e -> counts.put(e.getKey(), e.getValue()));
        return counts;
    }

    /** RowMapper: columns start at index 1. */
    static Alert alert(ResultSet rs, int rowNum) throws SQLException {
        return alertAt(rs, 0);
    }

    private static Alert alertAt(ResultSet rs, int o) throws SQLException {
        return new Alert(rs.getString(o + 1), rs.getString(o + 2), rs.getString(o + 3),
                AlertStatus.valueOf(rs.getString(o + 4)), Times.instant(rs.getTimestamp(o + 5)), rs.getString(o + 6),
                Times.instant(rs.getTimestamp(o + 7)), rs.getString(o + 8), Times.instant(rs.getTimestamp(o + 9)),
                rs.getString(o + 10), Times.instant(rs.getTimestamp(o + 11)), rs.getString(o + 12));
    }
}
