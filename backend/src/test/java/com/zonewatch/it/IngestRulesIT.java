package com.zonewatch.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;

/** R1, R2, R3, R6 and the alert rule, through the HTTP contract, including parallel delivery. */
class IngestRulesIT extends AbstractIT {

    // ---- R1 exactly-once effect -------------------------------------------------------------

    @Test
    void r1_sameBatchDeliveredInParallelManyTimesIsStoredOnce() throws Exception {
        String a = asset();
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            boolean inside = i == 25;
            lines.add(report(a + "-r" + i, a, T0.plusSeconds(i), inside ? IN_LON : OUT_LON, inside ? IN_LAT : OUT_LAT));
        }
        List<Callable<Resp>> tasks = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            tasks.add(() -> batch(lines));
        }
        List<Resp> results = parallel(tasks);

        int accepted = results.stream().mapToInt(r -> r.body().get("accepted").asInt()).sum();
        int duplicates = results.stream().mapToInt(r -> r.body().get("duplicates").asInt()).sum();
        assertThat(results).allMatch(r -> r.status() == 200);
        assertThat(accepted).isEqualTo(50);
        assertThat(duplicates).isEqualTo(50 * 11);
        assertThat(trackSize(a)).isEqualTo(50);
        assertThat(alerts(a)).isEqualTo(1);
        assertThat(currentReport(a)).isEqualTo(a + "-r49");
    }

    @Test
    void r1_redeliveryLaterChangesNothing() {
        String a = asset();
        List<String> lines = List.of(
                report(a + "-1", a, T0, OUT_LON, OUT_LAT),
                report(a + "-2", a, T0.plusSeconds(5), IN_LON, IN_LAT));
        batch(lines);
        long version = db.sql("SELECT version FROM asset_position WHERE asset_id = :a").param("a", a).query(Long.class).single();

        Resp again = batch(lines);
        assertThat(again.body().get("accepted").asInt()).isZero();
        assertThat(again.body().get("duplicates").asInt()).isEqualTo(2);
        assertThat(db.sql("SELECT version FROM asset_position WHERE asset_id = :a").param("a", a).query(Long.class).single())
                .isEqualTo(version);
        assertThat(trackSize(a)).isEqualTo(2);
        assertThat(alerts(a)).isEqualTo(1);
        long audit = db.sql("SELECT count(*) FROM alert_audit au JOIN alert al USING (alert_id) WHERE al.asset_id = :a")
                .param("a", a).query(Long.class).single();
        assertThat(audit).isEqualTo(1);
    }

    @Test
    void r1_duplicateWithinOneRequestCountsAsDuplicate() {
        String a = asset();
        String line = report(a + "-1", a, T0, OUT_LON, OUT_LAT);
        Resp r = batch(List.of(line, line));
        assertThat(r.body().get("accepted").asInt()).isEqualTo(1);
        assertThat(r.body().get("duplicates").asInt()).isEqualTo(1);
    }

    @Test
    void r1_sameReportIdWithDifferentContentKeepsTheFirstAndIsRecorded() {
        String a = asset();
        batch(List.of(report(a + "-1", a, T0, OUT_LON, OUT_LAT)));
        Resp r = batch(List.of(report(a + "-1", a, T0, IN_LON, IN_LAT)));
        assertThat(r.body().get("duplicates").asInt()).isEqualTo(1);
        assertThat(alerts(a)).isZero();
        assertThat(db.sql("SELECT count(*) FROM report_conflict WHERE report_id = :r").param("r", a + "-1")
                .query(Long.class).single()).isEqualTo(1);
    }

    // ---- R2 event-time order ------------------------------------------------------------------

    @Test
    void r2_shuffledParallelDeliveryEndsAtTheNewestReport() throws Exception {
        String a = asset();
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            lines.add(report(a + "-" + i, a, T0.plusMillis(i * 731L), OUT_LON + (i % 7) * 0.01, OUT_LAT));
        }
        Collections.shuffle(lines, new Random(42));
        List<Callable<Resp>> tasks = new ArrayList<>();
        for (int b = 0; b < 40; b++) {
            List<String> chunk = lines.subList(b * 10, b * 10 + 10);
            tasks.add(() -> batch(chunk));
        }
        parallel(tasks);
        assertThat(currentReport(a)).isEqualTo(a + "-399");
        assertThat(trackSize(a)).isEqualTo(400);
    }

    @Test
    void r2_lateReportIsStoredButNeverMovesThePositionNorAlerts() {
        String a = asset();
        batch(List.of(report(a + "-new", a, T0.plusSeconds(10), OUT_LON, OUT_LAT)));
        Resp late = batch(List.of(report(a + "-late", a, T0.plusSeconds(5), IN_LON, IN_LAT)));
        assertThat(late.body().get("accepted").asInt()).isEqualTo(1);
        assertThat(currentReport(a)).isEqualTo(a + "-new");
        assertThat(trackSize(a)).isEqualTo(2);
        assertThat(alerts(a)).isZero();
    }

    @Test
    void r2_equalObservedAtIsOrderedByReportIdWhateverTheArrivalOrder() {
        String a1 = asset();
        String a2 = asset();
        batch(List.of(report(a1 + "-b", a1, T0, OUT_LON, OUT_LAT)));
        batch(List.of(report(a1 + "-a", a1, T0, IN_LON, IN_LAT)));
        batch(List.of(report(a2 + "-a", a2, T0, IN_LON, IN_LAT)));
        batch(List.of(report(a2 + "-b", a2, T0, OUT_LON, OUT_LAT)));
        assertThat(currentReport(a1)).isEqualTo(a1 + "-b");
        assertThat(currentReport(a2)).isEqualTo(a2 + "-b");
    }

    @Test
    void r2_reportsInsideOneBatchAreAppliedInEventTimeOrder() {
        String a = asset();
        // delivered newest first in one request: in, out(newest). The entry happened, then the exit.
        Resp r = batch(List.of(
                report(a + "-3", a, T0.plusSeconds(3), OUT_LON, OUT_LAT),
                report(a + "-2", a, T0.plusSeconds(2), IN_LON, IN_LAT),
                report(a + "-1", a, T0.plusSeconds(1), OUT_LON, OUT_LAT)));
        assertThat(r.body().get("accepted").asInt()).isEqualTo(3);
        assertThat(currentReport(a)).isEqualTo(a + "-3");
        assertThat(alerts(a)).isEqualTo(1);
    }

    // ---- R3 one active alert ------------------------------------------------------------------

    @Test
    void r3_concurrentEntriesNeverCreateASecondActiveAlert() throws Exception {
        String a = asset();
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            boolean inside = i % 2 == 0;
            lines.add(report(a + "-" + i, a, T0.plusSeconds(i), inside ? IN_LON : OUT_LON, inside ? IN_LAT : OUT_LAT));
        }
        Collections.shuffle(lines, new Random(7));
        List<Callable<Resp>> tasks = new ArrayList<>();
        for (int b = 0; b < 24; b++) {
            List<String> chunk = lines.subList(b * 5, b * 5 + 5);
            tasks.add(() -> batch(chunk));
        }
        parallel(tasks);
        assertThat(activeAlerts(a)).isEqualTo(1);
        assertThat(alerts(a)).isEqualTo(1);
    }

    @Test
    void r3_afterResolveTheNextEntryRaisesANewAlertButStayingInsideDoesNot() {
        String a = asset();
        batch(List.of(report(a + "-1", a, T0, IN_LON, IN_LAT)));
        String first = db.sql("SELECT alert_id FROM alert WHERE asset_id = :a").param("a", a).query(String.class).single();
        // staying inside, and leaving then entering while OPEN: nothing new
        batch(List.of(report(a + "-2", a, T0.plusSeconds(1), IN_LON + 0.01, IN_LAT)));
        batch(List.of(report(a + "-3", a, T0.plusSeconds(2), OUT_LON, OUT_LAT)));
        batch(List.of(report(a + "-4", a, T0.plusSeconds(3), IN_LON, IN_LAT)));
        assertThat(alerts(a)).isEqualTo(1);

        post("/api/v1/alerts/" + first + "/claim", "op01", null);
        assertThat(post("/api/v1/alerts/" + first + "/resolve", "op01", "{\"note\":\"checked\"}").status()).isEqualTo(200);
        // still inside after the resolve: no new alert
        batch(List.of(report(a + "-5", a, T0.plusSeconds(4), IN_LON + 0.02, IN_LAT)));
        assertThat(alerts(a)).isEqualTo(1);
        // leave and enter again: a new alert
        batch(List.of(report(a + "-6", a, T0.plusSeconds(5), OUT_LON, OUT_LAT)));
        batch(List.of(report(a + "-7", a, T0.plusSeconds(6), IN_LON, IN_LAT)));
        assertThat(alerts(a)).isEqualTo(2);
        assertThat(activeAlerts(a)).isEqualTo(1);
    }

    @Test
    void r3_theDatabaseRefusesASecondActiveAlert() {
        String alertId = newAlert();
        String a = db.sql("SELECT asset_id FROM alert WHERE alert_id = :id").param("id", alertId).query(String.class).single();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> db.sql("""
                        INSERT INTO alert (alert_id, asset_id, zone_id, status, opened_at, trigger_report_id, trigger_observed_at)
                        VALUES ('x-' || :a, :a, 'Z-01', 'OPEN', now(), 'r', now())
                        """).param("a", a).update())
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    // ---- alert rule ---------------------------------------------------------------------------

    @Test
    void alertRule_firstPositionInsideRaisesAnAlert() {
        String a = asset();
        batch(List.of(report(a + "-1", a, T0, IN_LON, IN_LAT)));
        assertThat(alerts(a)).isEqualTo(1);
        var row = db.sql("SELECT zone_id, trigger_report_id FROM alert WHERE asset_id = :a").param("a", a)
                .query((rs, n) -> List.of(rs.getString(1), rs.getString(2))).single();
        assertThat(row).containsExactly("Z-01", a + "-1");
    }

    @Test
    void alertRule_holesAndAdvisoryZonesRaiseNothing() {
        String hole = asset();
        String advisory = asset();
        batch(List.of(report(hole + "-1", hole, T0, 173.2, 39.6)));      // hole of Z-03
        batch(List.of(report(advisory + "-1", advisory, T0, 169.9, 40.8))); // Z-09 ADVISORY
        assertThat(alerts(hole)).isZero();
        assertThat(alerts(advisory)).isZero();
    }

    @Test
    void alertRule_overlappingZonesEachRaiseTheirOwnAlert() {
        String a = asset();
        batch(List.of(report(a + "-1", a, T0, 174.8, 32.6))); // Z-04 and Z-05
        List<String> zones = db.sql("SELECT zone_id FROM alert WHERE asset_id = :a ORDER BY zone_id").param("a", a)
                .query(String.class).list();
        assertThat(zones).containsExactly("Z-04", "Z-05");
    }

    @Test
    void alertRule_zoneAcrossTheAntimeridian() {
        String east = asset();
        String west = asset();
        String far = asset();
        batch(List.of(report(east + "-1", east, T0, 179.6, 34.5)));
        batch(List.of(report(west + "-1", west, T0, -179.6, 34.5)));
        batch(List.of(report(far + "-1", far, T0, 0.0, 34.5)));
        assertThat(alerts(east)).isEqualTo(1);
        assertThat(alerts(west)).isEqualTo(1);
        assertThat(alerts(far)).isZero();
    }

    // ---- R6 bad input is isolated -------------------------------------------------------------

    @Test
    void r6_badLinesAreRejectedOneByOneAndGoodLinesInTheSameRequestAreStored() {
        String a = asset();
        Resp r = batch(List.of(
                report(a + "-1", a, T0, OUT_LON, OUT_LAT),
                "{\"reportId\":\"" + a + "-x\",\"assetId\":\"" + a,
                report(a + "-2", a, T0.plusSeconds(1), OUT_LON, OUT_LAT),
                "{\"reportId\":\"" + a + "-y\",\"observedAt\":\"2026-11-02T06:00:00Z\",\"lat\":1,\"lon\":2}",
                "\"heartbeat\"",
                report(a + "-3", a, T0.plusSeconds(2), 999, OUT_LAT),
                report(a + "-4", a, T0.plusSeconds(3), IN_LON, IN_LAT)));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("received").asInt()).isEqualTo(7);
        assertThat(r.body().get("accepted").asInt()).isEqualTo(3);
        assertThat(r.body().get("rejected").asInt()).isEqualTo(4);
        assertThat(r.body().get("rejections").findValuesAsText("code"))
                .containsExactly("MALFORMED_JSON", "MISSING_FIELD", "MALFORMED_JSON", "INVALID_COORDINATE");
        assertThat(r.body().get("rejections").findValues("line").stream().map(n -> n.asInt()))
                .containsExactly(2, 4, 5, 6);
        assertThat(currentReport(a)).isEqualTo(a + "-4");
        assertThat(alerts(a)).isEqualTo(1);

        String requestId = r.raw().headers().firstValue("X-Request-Id").orElseThrow();
        List<String> stored = db.sql("SELECT code FROM rejected_line WHERE request_id = :r ORDER BY line_no")
                .param("r", requestId).query(String.class).list();
        assertThat(stored).containsExactly("MALFORMED_JSON", "MISSING_FIELD", "MALFORMED_JSON", "INVALID_COORDINATE");
        Resp inspect = get("/api/v1/rejections?limit=50", "sup01");
        assertThat(inspect.body().get("items").findValuesAsText("raw")).contains("\"heartbeat\"");
    }

    @Test
    void r6_aBatchOfOnlyBadLinesInParallelWithGoodBatchesBlocksNothing() throws Exception {
        String a = asset();
        List<Callable<Resp>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            int n = i;
            tasks.add(() -> batch(List.of("not json at all {", "{\"lat\":\"x\"}", "[]")));
            tasks.add(() -> batch(List.of(report(a + "-" + n, a, T0.plusSeconds(n), OUT_LON, OUT_LAT))));
        }
        List<Resp> results = parallel(tasks);
        assertThat(results).allMatch(r -> r.status() == 200);
        assertThat(trackSize(a)).isEqualTo(6);
        assertThat(currentReport(a)).isEqualTo(a + "-5");
    }

    @Test
    void r6_aStringThatPostgresCannotStoreIsRejectedNotFatal() {
        String a = asset();
        Resp r = batch(List.of(
                report("bad\\u0000id", a, T0, OUT_LON, OUT_LAT),
                report(a + "-ok", a, T0, OUT_LON, OUT_LAT)));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("accepted").asInt()).isEqualTo(1);
        assertThat(r.body().get("rejected").asInt()).isEqualTo(1);
    }

    @Test
    void futureSentinelTimestampIsRejected() {
        String a = asset();
        Resp r = batch(List.of(report(a + "-1", a, Instant.parse("2031-01-01T00:00:00Z"), OUT_LON, OUT_LAT)));
        assertThat(r.body().get("rejections").get(0).get("code").asText()).isEqualTo("INVALID_TIMESTAMP");
    }
}
