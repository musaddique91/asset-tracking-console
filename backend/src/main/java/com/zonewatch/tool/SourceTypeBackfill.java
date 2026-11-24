package com.zonewatch.tool;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CR-1, step 2 of 4: fills report.source_type for rows written before the upgrade.
 *
 * Walks the primary key in order (keyset, no OFFSET), batchSize keys at a time. Each batch is its own
 * short transaction with lock_timeout and statement_timeout, touches only rows that are still NULL,
 * and is followed by a pause, so live ingest never waits long on it. Stop it at any time; run it again
 * (from the beginning, or from the last key it logged) and it continues: it is idempotent.
 */
public class SourceTypeBackfill {

    private static final Logger log = LoggerFactory.getLogger(SourceTypeBackfill.class);

    public record Result(long scanned, long updated, String lastKey, long millis) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public SourceTypeBackfill(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public Result run(int batchSize, Duration pause, String fromKey) {
        long start = System.currentTimeMillis();
        long scanned = 0;
        long updated = 0;
        long lastLog = start;
        String last = fromKey == null ? "" : fromKey;
        while (true) {
            final String after = last;
            long[] counts = new long[2];
            String next = tx.execute(status -> {
                jdbc.execute("SET LOCAL lock_timeout = '2s'");
                jdbc.execute("SET LOCAL statement_timeout = '30s'");
                return jdbc.queryForObject("""
                                WITH batch AS (
                                    SELECT report_id FROM report WHERE report_id > ? ORDER BY report_id LIMIT ?
                                ), filled AS (
                                    UPDATE report r SET source_type = report_source_type(r.source)
                                    FROM batch b
                                    WHERE r.report_id = b.report_id AND r.source_type IS NULL
                                    RETURNING 1
                                )
                                SELECT (SELECT max(report_id) FROM batch), (SELECT count(*) FROM batch), (SELECT count(*) FROM filled)
                                """,
                        (rs, n) -> {
                            counts[0] = rs.getLong(2);
                            counts[1] = rs.getLong(3);
                            return rs.getString(1);
                        },
                        after, batchSize);
            });
            if (counts[0] == 0 || next == null) {
                break;
            }
            scanned += counts[0];
            updated += counts[1];
            last = next;
            long now = System.currentTimeMillis();
            if (now - lastLog >= 5000) {
                lastLog = now;
                log.info("backfill progress: scanned={} updated={} rate={} rows/s lastKey={}", scanned, updated,
                        scanned * 1000 / Math.max(1, now - start), last);
            }
            if (!pause.isZero()) {
                try {
                    Thread.sleep(pause.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        long millis = System.currentTimeMillis() - start;
        long remaining = jdbc.queryForObject("SELECT count(*) FROM report WHERE source_type IS NULL", Long.class);
        log.info("backfill finished: scanned={} updated={} in {} ms ({} rows/s), rows still NULL: {}", scanned, updated,
                millis, scanned * 1000 / Math.max(1, millis), remaining);
        return new Result(scanned, updated, last, millis);
    }
}
