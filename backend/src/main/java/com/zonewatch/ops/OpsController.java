package com.zonewatch.ops;

import com.zaxxer.hikari.HikariDataSource;
import com.zonewatch.alert.AlertMetrics;
import com.zonewatch.common.Times;
import com.zonewatch.config.ZwProperties;
import com.zonewatch.ingest.IngestMetrics;
import com.zonewatch.stream.ChangePoller;
import com.zonewatch.stream.StreamHub;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/v1/ops/metrics (SUPERVISOR): what this instance is doing, readable without any external
 * monitoring service. The load balancer picks the instance; X-Served-By in the response names it.
 */
@RestController
public class OpsController {

    private final ZwProperties props;
    private final IngestMetrics ingest;
    private final AlertMetrics alerts;
    private final StreamHub hub;
    private final ObjectProvider<ChangePoller> poller;
    private final DataSource dataSource;
    private final MeterRegistry registry;

    public OpsController(ZwProperties props, IngestMetrics ingest, AlertMetrics alerts, StreamHub hub,
                         ObjectProvider<ChangePoller> poller, DataSource dataSource, MeterRegistry registry) {
        this.props = props;
        this.ingest = ingest;
        this.alerts = alerts;
        this.hub = hub;
        this.poller = poller;
        this.dataSource = dataSource;
        this.registry = registry;
    }

    @GetMapping("/api/v1/ops/metrics")
    public Map<String, Object> metrics() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instance", props.instance());
        m.put("uptimeSec", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        m.put("ingestLinesPerSecond10s", ingest.recentLinesPerSecond());
        m.put("ingest", ingest.snapshot());
        m.put("alertsOpened", alerts.openedCount());
        m.put("liveClients", hub.clientCount());
        ChangePoller p = poller.getIfAvailable();
        if (p != null) {
            m.put("livePollLast", Times.format(p.lastPoll()));
            m.put("livePollMs", p.lastPollMs());
        }
        if (dataSource instanceof HikariDataSource h && h.getHikariPoolMXBean() != null) {
            var pool = h.getHikariPoolMXBean();
            m.put("dbPool", Map.of("active", pool.getActiveConnections(), "idle", pool.getIdleConnections(),
                    "waiting", pool.getThreadsAwaitingConnection(), "max", h.getMaximumPoolSize()));
        }
        double serverErrors = Search.in(registry).name("http.server.requests").tag("outcome", "SERVER_ERROR")
                .timers().stream().mapToDouble(t -> t.count()).sum();
        double clientErrors = Search.in(registry).name("http.server.requests").tag("outcome", "CLIENT_ERROR")
                .timers().stream().mapToDouble(t -> t.count()).sum();
        m.put("http", Map.of("serverErrors", serverErrors, "clientErrors", clientErrors));
        return m;
    }
}
