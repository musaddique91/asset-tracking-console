package com.zonewatch.stream;

import com.zonewatch.alert.Alert;
import com.zonewatch.alert.AlertStatus;
import com.zonewatch.common.Times;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StreamSnapshot {

    static final int MAX_ACTIVE = 2000;

    private final JdbcClient jdbc;

    public StreamSnapshot(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Active (OPEN and CLAIMED) alerts with their versions, oldest first, bounded. */
    @Transactional(readOnly = true)
    public Map<String, Object> activeAlerts() {
        List<ChangePoller.AlertChange> items = jdbc.sql("""
                        SELECT alert_id, asset_id, zone_id, status, opened_at, trigger_report_id, trigger_observed_at,
                               claimed_by, claimed_at, resolved_by, resolved_at, resolution_note, version
                        FROM alert WHERE status IN ('OPEN', 'CLAIMED') ORDER BY alert_seq LIMIT :max
                        """)
                .param("max", MAX_ACTIVE + 1)
                .query((rs, n) -> new ChangePoller.AlertChange(new Alert(rs.getString(1), rs.getString(2),
                        rs.getString(3), AlertStatus.valueOf(rs.getString(4)), Times.instant(rs.getTimestamp(5)),
                        rs.getString(6), Times.instant(rs.getTimestamp(7)), rs.getString(8),
                        Times.instant(rs.getTimestamp(9)), rs.getString(10), Times.instant(rs.getTimestamp(11)),
                        rs.getString(12)), rs.getLong(13)))
                .list();
        boolean truncated = items.size() > MAX_ACTIVE;
        return Map.of("items", truncated ? items.subList(0, MAX_ACTIVE) : items, "truncated", truncated);
    }
}
