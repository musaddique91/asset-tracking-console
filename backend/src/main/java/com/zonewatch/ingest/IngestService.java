package com.zonewatch.ingest;

import com.zonewatch.config.ZwProperties;
import com.zonewatch.ingest.IngestRepository.AlertCandidate;
import com.zonewatch.ingest.IngestRepository.Current;
import com.zonewatch.zone.ZoneRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Processes one batch in one transaction:
 *
 * 1. validate every line on its own (R6) - rejects never touch the database transaction's outcome
 * 2. insert new reports, ON CONFLICT DO NOTHING (R1): only rows really inserted go further
 * 3. lock the position rows of the batch's assets in asset order (R2, both instances serialise per asset)
 * 4. per asset, apply the accepted reports in event-time order: a report newer than the current
 *    position moves it and may open alerts on entry into a restricted zone; an older one is late
 *    and only stays in the track (R2)
 * 5. open alerts guarded by the partial unique index, with the OPENED audit entry (R3, R5)
 * 6. store rejected lines and the batch totals
 *
 * Deadlocks and serialisation failures roll back the whole batch, which is then retried: every step
 * is idempotent, so a retry, like a re-delivery, changes nothing that already happened.
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);
    private static final int MAX_ATTEMPTS = 5;

    private final IngestRepository repo;
    private final ZoneRegistry zones;
    private final TransactionTemplate tx;
    private final IngestMetrics metrics;
    private final ReportValidator validator;
    private final String instance;

    public IngestService(IngestRepository repo, ZoneRegistry zones, TransactionTemplate tx, IngestMetrics metrics,
                         ZwProperties props, Clock clock) {
        this.repo = repo;
        this.zones = zones;
        this.tx = tx;
        this.metrics = metrics;
        this.instance = props.instance();
        Duration skew = props.maxFutureSkew() == null ? Duration.ofHours(1) : props.maxFutureSkew();
        this.validator = new ReportValidator(clock, skew);
    }

    private record Outcome(BatchResult result, int conflicts, int opened) {
    }

    public BatchResult ingest(List<String> lines, String requestId) {
        long start = System.nanoTime();
        List<Report> valid = new ArrayList<>(lines.size());
        List<Rejection> rejections = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Object outcome = validator.validate(i + 1, lines.get(i));
            if (outcome instanceof Report r) {
                valid.add(r);
            } else {
                rejections.add((Rejection) outcome);
            }
        }
        Outcome outcome = null;
        for (int attempt = 1; outcome == null; attempt++) {
            try {
                outcome = tx.execute(status -> store(lines.size(), valid, rejections, requestId, start));
            } catch (TransientDataAccessException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                metrics.retried();
                log.warn("batch attempt {} rolled back ({}), retrying", attempt, e.getClass().getSimpleName());
                sleep(attempt);
            }
        }
        metrics.record(outcome.result(), outcome.conflicts(), outcome.opened(), System.nanoTime() - start);
        return outcome.result();
    }

    private Outcome store(int received, List<Report> valid, List<Rejection> rejections, String requestId, long start) {
        // first occurrence of each reportId in this request is the candidate; repeats are duplicates
        Map<String, Report> candidates = new LinkedHashMap<>();
        List<Report> repeats = new ArrayList<>();
        for (Report r : valid) {
            if (candidates.putIfAbsent(r.reportId(), r) != null) {
                repeats.add(r);
            }
        }
        Set<String> inserted = repo.insertReports(new ArrayList<>(candidates.values()));

        List<Report> accepted = new ArrayList<>(inserted.size());
        List<Report> redelivered = new ArrayList<>(repeats);
        for (Report r : candidates.values()) {
            (inserted.contains(r.reportId()) ? accepted : redelivered).add(r);
        }
        List<Report> conflicts = findConflicts(redelivered, candidates, inserted);
        int opened = applyPositions(accepted);

        repo.insertRejections(rejections, requestId);
        repo.insertConflicts(conflicts, requestId);
        int durationMs = (int) ((System.nanoTime() - start) / 1_000_000);
        repo.recordBatch(instance, requestId, received, accepted.size(), redelivered.size(), rejections.size(), durationMs);
        BatchResult result = new BatchResult(received, accepted.size(), redelivered.size(), rejections.size(), rejections);
        return new Outcome(result, conflicts.size(), opened);
    }

    /** A redelivered reportId whose content differs from the stored report. The stored one stays. */
    private List<Report> findConflicts(List<Report> redelivered, Map<String, Report> candidates, Set<String> inserted) {
        if (redelivered.isEmpty()) {
            return List.of();
        }
        Set<String> ids = new HashSet<>();
        for (Report r : redelivered) {
            if (!inserted.contains(r.reportId())) {
                ids.add(r.reportId());
            }
        }
        Map<String, Report> stored = new HashMap<>(repo.storedReports(ids));
        for (String id : inserted) {
            stored.put(id, candidates.get(id));
        }
        List<Report> conflicts = new ArrayList<>();
        for (Report r : redelivered) {
            Report s = stored.get(r.reportId());
            if (s != null && !r.sameContent(s.assetId(), s.observedAt(), s.lat(), s.lon(), s.speedKn(), s.headingDeg(),
                    s.source(), s.kind())) {
                conflicts.add(r);
            }
        }
        if (!conflicts.isEmpty()) {
            log.warn("{} reports re-delivered with different content; stored version kept", conflicts.size());
        }
        return conflicts;
    }

    private static final Comparator<Report> EVENT_ORDER =
            Comparator.comparing(Report::observedAt).thenComparing(Report::reportId);

    /** Returns the number of alerts opened. */
    private int applyPositions(List<Report> accepted) {
        if (accepted.isEmpty()) {
            return 0;
        }
        TreeMap<String, List<Report>> byAsset = new TreeMap<>();
        for (Report r : accepted) {
            byAsset.computeIfAbsent(r.assetId(), k -> new ArrayList<>()).add(r);
        }
        List<Report> firsts = new ArrayList<>(byAsset.size());
        for (List<Report> reports : byAsset.values()) {
            reports.sort(EVENT_ORDER);
            firsts.add(reports.get(0));
        }
        Set<String> created = repo.insertNewAssets(firsts);
        Map<String, Current> current = repo.lockPositions(new ArrayList<>(byAsset.keySet()));

        List<Report> moved = new ArrayList<>();
        List<AlertCandidate> entries = new ArrayList<>();
        Set<String> seenEntry = new HashSet<>();
        for (Map.Entry<String, List<Report>> e : byAsset.entrySet()) {
            String assetId = e.getKey();
            List<Report> reports = e.getValue();
            Current cur = current.get(assetId);
            int from = 0;
            Set<String> inside;
            String curReport = cur.reportId();
            java.time.Instant curObserved = cur.observedAt();
            if (created.contains(assetId)) {
                // first position of a new asset: entering from "no position at all"
                Report first = reports.get(0);
                inside = zones.restrictedZonesAt(first.lon(), first.lat());
                addEntries(entries, seenEntry, first, Set.of(), inside);
                from = 1;
            } else {
                inside = zones.restrictedZonesAt(cur.lon(), cur.lat());
            }
            Report newest = null;
            for (int i = from; i < reports.size(); i++) {
                Report r = reports.get(i);
                if (r.compareKey(curObserved, curReport) <= 0) {
                    continue; // late: kept in the track, never moves the position, never alerts
                }
                Set<String> now = zones.restrictedZonesAt(r.lon(), r.lat());
                addEntries(entries, seenEntry, r, inside, now);
                inside = now;
                curObserved = r.observedAt();
                curReport = r.reportId();
                newest = r;
            }
            if (newest != null) {
                moved.add(newest);
            }
        }
        repo.updatePositions(moved);
        return repo.openAlerts(entries).size();
    }

    private static void addEntries(List<AlertCandidate> out, Set<String> seen, Report r, Set<String> before, Set<String> after) {
        for (String zoneId : after) {
            if (!before.contains(zoneId) && seen.add(r.assetId() + '\u0000' + zoneId)) {
                out.add(new AlertCandidate(UUID.randomUUID().toString(), r.assetId(), zoneId, r.reportId(), r.observedAt()));
            }
        }
    }

    private static void sleep(int attempt) {
        try {
            Thread.sleep(20L * attempt + (long) (Math.random() * 30));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
