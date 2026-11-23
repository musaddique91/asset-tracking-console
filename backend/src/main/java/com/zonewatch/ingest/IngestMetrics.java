package com.zonewatch.ingest;

import com.zonewatch.alert.AlertMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLongArray;
import org.springframework.stereotype.Component;

/** Per-instance ingest metrics: line outcomes, rejections by code, batch latency, recent line rate. */
@Component
public class IngestMetrics {

    private final Counter accepted;
    private final Counter duplicates;
    private final Counter rejected;
    private final Counter conflicts;
    private final Counter retries;
    private final Map<RejectionCode, Counter> byCode = new EnumMap<>(RejectionCode.class);
    private final Timer batchTimer;
    private final AlertMetrics alerts;
    // lines received per second over the last 60 seconds (ring buffer by epoch second)
    private final AtomicLongArray perSecond = new AtomicLongArray(60);
    private final AtomicLongArray secondOf = new AtomicLongArray(60);

    public IngestMetrics(MeterRegistry registry, AlertMetrics alerts) {
        this.alerts = alerts;
        this.accepted = Counter.builder("zw.ingest.lines").tag("outcome", "accepted").register(registry);
        this.duplicates = Counter.builder("zw.ingest.lines").tag("outcome", "duplicate").register(registry);
        this.rejected = Counter.builder("zw.ingest.lines").tag("outcome", "rejected").register(registry);
        this.conflicts = Counter.builder("zw.ingest.conflicting.duplicates").register(registry);
        this.retries = Counter.builder("zw.ingest.batch.retries").register(registry);
        for (RejectionCode code : RejectionCode.values()) {
            byCode.put(code, Counter.builder("zw.ingest.rejections").tag("code", code.name()).register(registry));
        }
        this.batchTimer = Timer.builder("zw.ingest.batch").publishPercentiles(0.5, 0.95, 0.99).register(registry);
    }

    void record(BatchResult result, int conflictCount, int openedAlerts, long nanos) {
        accepted.increment(result.accepted());
        duplicates.increment(result.duplicates());
        rejected.increment(result.rejected());
        conflicts.increment(conflictCount);
        result.rejections().forEach(r -> byCode.get(r.code()).increment());
        batchTimer.record(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
        alerts.opened(openedAlerts);
        long now = System.currentTimeMillis() / 1000;
        int slot = (int) (now % 60);
        if (secondOf.getAndSet(slot, now) != now) {
            perSecond.set(slot, 0);
        }
        perSecond.addAndGet(slot, result.received());
    }

    void retried() {
        retries.increment();
    }

    /** Lines per second received by this instance, averaged over the last 10 full seconds. */
    public double recentLinesPerSecond() {
        long now = System.currentTimeMillis() / 1000;
        long sum = 0;
        for (int i = 1; i <= 10; i++) {
            long sec = now - i;
            int slot = (int) (sec % 60);
            if (secondOf.get(slot) == sec) {
                sum += perSecond.get(slot);
            }
        }
        return sum / 10.0;
    }

    public Map<String, Double> snapshot() {
        Map<String, Double> m = new java.util.LinkedHashMap<>();
        m.put("accepted", accepted.count());
        m.put("duplicates", duplicates.count());
        m.put("rejected", rejected.count());
        m.put("conflictingDuplicates", conflicts.count());
        m.put("batchRetries", retries.count());
        m.put("batches", (double) batchTimer.count());
        m.put("batchMeanMs", batchTimer.mean(java.util.concurrent.TimeUnit.MILLISECONDS));
        m.put("batchMaxMs", batchTimer.max(java.util.concurrent.TimeUnit.MILLISECONDS));
        byCode.forEach((code, c) -> m.put("rejected." + code.name(), c.count()));
        return m;
    }
}
