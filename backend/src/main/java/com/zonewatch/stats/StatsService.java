package com.zonewatch.stats;

import com.zonewatch.alert.AlertRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StatsService {

    private final JdbcClient jdbc;
    private final AlertRepository alerts;

    public StatsService(JdbcClient jdbc, AlertRepository alerts) {
        this.jdbc = jdbc;
        this.alerts = alerts;
    }

    /** One snapshot (REPEATABLE READ) so the numbers are consistent with each other. */
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String, Object> stats() {
        long[] totals = jdbc.sql("""
                        SELECT coalesce(sum(received), 0), coalesce(sum(accepted), 0),
                               coalesce(sum(duplicates), 0), coalesce(sum(rejected), 0)
                        FROM ingest_batch
                        """)
                .query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)}).single();
        long assets = jdbc.sql("SELECT count(*) FROM asset_position").query(Long.class).single();
        Map<String, Long> byStatus = alerts.countsByStatus();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("received", totals[0]);
        out.put("accepted", totals[1]);
        out.put("duplicates", totals[2]);
        out.put("rejected", totals[3]);
        out.put("assets", assets);
        Map<String, Long> a = new LinkedHashMap<>();
        a.put("open", byStatus.get("OPEN"));
        a.put("claimed", byStatus.get("CLAIMED"));
        a.put("resolved", byStatus.get("RESOLVED"));
        out.put("alerts", a);
        return out;
    }
}
