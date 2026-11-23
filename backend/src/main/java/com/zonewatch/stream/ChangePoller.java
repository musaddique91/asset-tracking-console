package com.zonewatch.stream;

import com.zonewatch.alert.Alert;
import com.zonewatch.alert.AlertStatus;
import com.zonewatch.common.Times;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Finds what changed in the database and pushes it to this instance's live clients. Changes made by
 * the other instance are found the same way, so both instances serve the same live view without a
 * broker. Rows are read with an overlap window (a row committed late, with an older changed_at, is
 * still seen) and de-duplicated by version, so each change is pushed once per instance.
 */
@Component
@ConditionalOnProperty(name = "zw.mode", havingValue = "serve", matchIfMissing = true)
public class ChangePoller {

    private static final Logger log = LoggerFactory.getLogger(ChangePoller.class);
    private static final String OVERLAP = "2 seconds";
    private static final int MAX_ROWS = 20000;

    /** [assetId, lat, lon, headingDeg, kind, observedAt, reportId, speedKn] */
    public record PositionEvent(List<Object[]> items) {
    }

    public record AlertChange(Alert alert, long version) {
    }

    public record AlertEvent(List<AlertChange> items) {
    }

    private final JdbcClient jdbc;
    private final StreamHub hub;
    private final Map<String, Long> sentPositionVersion = new ConcurrentHashMap<>();
    private final Map<String, Long> sentAlertVersion = new ConcurrentHashMap<>();
    private Timestamp positionMark;
    private Timestamp alertMark;
    private volatile Instant lastPoll = Instant.EPOCH;
    private volatile long lastPollMs;

    public ChangePoller(JdbcClient jdbc, StreamHub hub) {
        this.jdbc = jdbc;
        this.hub = hub;
    }

    @Scheduled(fixedDelayString = "${zw.stream-poll-interval:PT0.25S}")
    public void poll() {
        long start = System.nanoTime();
        try {
            if (positionMark == null) {
                Timestamp now = jdbc.sql("SELECT clock_timestamp()").query(Timestamp.class).single();
                positionMark = now;
                alertMark = now;
            }
            pollPositions();
            pollAlerts();
        } catch (RuntimeException e) {
            log.warn("live change poll failed: {}", e.getMessage());
        } finally {
            lastPoll = Instant.now();
            lastPollMs = (System.nanoTime() - start) / 1_000_000;
        }
    }

    private void pollPositions() {
        record Row(Object[] item, long version, Timestamp changedAt) {
        }
        List<Row> rows = jdbc.sql("""
                        SELECT asset_id, lat, lon, heading_deg, kind, observed_at, report_id, speed_kn, version, changed_at
                        FROM asset_position WHERE changed_at > CAST(:mark AS timestamptz) - CAST(:overlap AS interval)
                        ORDER BY changed_at LIMIT :max
                        """)
                .param("mark", positionMark.toInstant().toString()).param("overlap", OVERLAP).param("max", MAX_ROWS)
                .query((rs, n) -> new Row(new Object[] {rs.getString(1), rs.getDouble(2), rs.getDouble(3),
                        rs.getObject(4), rs.getString(5), Times.format(rs.getTimestamp(6).toInstant()), rs.getString(7),
                        rs.getObject(8)}, rs.getLong(9), rs.getTimestamp(10)))
                .list();
        List<Object[]> fresh = new ArrayList<>();
        for (Row r : rows) {
            String assetId = (String) r.item()[0];
            Long sent = sentPositionVersion.get(assetId);
            if (sent == null || r.version() > sent) {
                sentPositionVersion.put(assetId, r.version());
                fresh.add(r.item());
            }
            if (r.changedAt().after(positionMark)) {
                positionMark = r.changedAt();
            }
        }
        if (!fresh.isEmpty() && hub.hasClients()) {
            hub.broadcast("positions", new PositionEvent(fresh));
        }
    }

    private void pollAlerts() {
        record Row(AlertChange change, Timestamp changedAt) {
        }
        List<Row> rows = jdbc.sql("""
                        SELECT alert_id, asset_id, zone_id, status, opened_at, trigger_report_id, trigger_observed_at,
                               claimed_by, claimed_at, resolved_by, resolved_at, resolution_note, version, changed_at
                        FROM alert WHERE changed_at > CAST(:mark AS timestamptz) - CAST(:overlap AS interval)
                        ORDER BY changed_at LIMIT :max
                        """)
                .param("mark", alertMark.toInstant().toString()).param("overlap", OVERLAP).param("max", MAX_ROWS)
                .query((rs, n) -> new Row(new AlertChange(new Alert(rs.getString(1), rs.getString(2), rs.getString(3),
                        AlertStatus.valueOf(rs.getString(4)), Times.instant(rs.getTimestamp(5)), rs.getString(6),
                        Times.instant(rs.getTimestamp(7)), rs.getString(8), Times.instant(rs.getTimestamp(9)),
                        rs.getString(10), Times.instant(rs.getTimestamp(11)), rs.getString(12)), rs.getLong(13)),
                        rs.getTimestamp(14)))
                .list();
        List<AlertChange> fresh = new ArrayList<>();
        for (Row r : rows) {
            String id = r.change().alert().alertId();
            Long sent = sentAlertVersion.get(id);
            if (sent == null || r.change().version() > sent) {
                sentAlertVersion.put(id, r.change().version());
                fresh.add(r.change());
            }
            if (r.changedAt().after(alertMark)) {
                alertMark = r.changedAt();
            }
        }
        if (!fresh.isEmpty() && hub.hasClients()) {
            hub.broadcast("alerts", new AlertEvent(fresh));
        }
    }

    public Instant lastPoll() {
        return lastPoll;
    }

    public long lastPollMs() {
        return lastPollMs;
    }
}
