package com.zonewatch.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;

/** R4, R5 and the alert lifecycle (brief table 6), including concurrent commands. */
class AlertWorkflowIT extends AbstractIT {

    private List<String> auditActions(String alertId) {
        return get("/api/v1/alerts/" + alertId + "/audit", "op01").body().get("items").findValuesAsText("action");
    }

    // ---- R4 one owner -------------------------------------------------------------------------

    @Test
    void r4_twentyOperatorsClaimAtTheSameMomentAndExactlyOneWins() throws Exception {
        String id = newAlert();
        List<Callable<Resp>> tasks = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            String user = String.format("op%02d", i);
            tasks.add(() -> post("/api/v1/alerts/" + id + "/claim", user, null));
        }
        List<Resp> results = parallel(tasks);
        List<Resp> won = results.stream().filter(r -> r.status() == 200).toList();
        assertThat(won).hasSize(1);
        assertThat(results.stream().filter(r -> r.status() == 409))
                .hasSize(19)
                .allMatch(r -> "ALERT_ALREADY_CLAIMED".equals(r.code()));
        String owner = won.get(0).body().get("claimedBy").asText();
        assertThat(get("/api/v1/alerts/" + id, "op01").body().get("claimedBy").asText()).isEqualTo(owner);
        assertThat(auditActions(id)).containsExactly("OPENED", "CLAIMED");
    }

    @Test
    void claimRepeatedByTheOwnerChangesNothing() {
        String id = newAlert();
        assertThat(post("/api/v1/alerts/" + id + "/claim", "op01", null).status()).isEqualTo(200);
        Resp again = post("/api/v1/alerts/" + id + "/claim", "op01", null);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().get("status").asText()).isEqualTo("CLAIMED");
        assertThat(auditActions(id)).containsExactly("OPENED", "CLAIMED");
    }

    @Test
    void releaseRules() {
        String id = newAlert();
        assertThat(post("/api/v1/alerts/" + id + "/release", "op01", null).code()).isEqualTo("INVALID_STATE");
        post("/api/v1/alerts/" + id + "/claim", "op01", null);
        Resp other = post("/api/v1/alerts/" + id + "/release", "op02", null);
        assertThat(other.status()).isEqualTo(403);
        assertThat(other.code()).isEqualTo("NOT_ALERT_OWNER");
        Resp own = post("/api/v1/alerts/" + id + "/release", "op01", null);
        assertThat(own.status()).isEqualTo(200);
        assertThat(own.body().get("status").asText()).isEqualTo("OPEN");
        assertThat(own.body().get("claimedBy").isNull()).isTrue();
        post("/api/v1/alerts/" + id + "/claim", "op02", null);
        assertThat(post("/api/v1/alerts/" + id + "/release", "sup01", null).status()).isEqualTo(200);
        assertThat(auditActions(id)).containsExactly("OPENED", "CLAIMED", "RELEASED", "CLAIMED", "RELEASED");
    }

    @Test
    void resolveRules() {
        String id = newAlert();
        assertThat(post("/api/v1/alerts/" + id + "/resolve", "op01", "{\"note\":\"x\"}").code()).isEqualTo("INVALID_STATE");
        post("/api/v1/alerts/" + id + "/claim", "op01", null);
        assertThat(post("/api/v1/alerts/" + id + "/resolve", "op01", "{\"note\":\"   \"}").code()).isEqualTo("VALIDATION_FAILED");
        assertThat(post("/api/v1/alerts/" + id + "/resolve", "op01", "{}").code()).isEqualTo("VALIDATION_FAILED");
        assertThat(post("/api/v1/alerts/" + id + "/resolve", "op01", "{\"note\":\"" + "n".repeat(501) + "\"}").code())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(post("/api/v1/alerts/" + id + "/resolve", "op02", "{\"note\":\"mine\"}").code()).isEqualTo("NOT_ALERT_OWNER");

        Resp ok = post("/api/v1/alerts/" + id + "/resolve", "op01", "{\"note\":\"" + "n".repeat(500) + "\"}");
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body().get("status").asText()).isEqualTo("RESOLVED");
        assertThat(ok.body().get("resolvedBy").asText()).isEqualTo("op01");
        assertThat(ok.body().get("resolutionNote").asText()).hasSize(500);

        assertThat(post("/api/v1/alerts/" + id + "/resolve", "op01", "{\"note\":\"again\"}").status()).isEqualTo(200);
        assertThat(post("/api/v1/alerts/" + id + "/resolve", "sup01", "{\"note\":\"again\"}").code()).isEqualTo("INVALID_STATE");
        assertThat(post("/api/v1/alerts/" + id + "/claim", "op02", null).code()).isEqualTo("INVALID_STATE");
        assertThat(post("/api/v1/alerts/" + id + "/release", "op01", null).code()).isEqualTo("INVALID_STATE");
        assertThat(auditActions(id)).containsExactly("OPENED", "CLAIMED", "RESOLVED");
    }

    @Test
    void supervisorMayResolveAnAlertOwnedBySomebodyElse() {
        String id = newAlert();
        post("/api/v1/alerts/" + id + "/claim", "op03", null);
        Resp r = post("/api/v1/alerts/" + id + "/resolve", "sup01", "{\"note\":\"closed by supervisor\"}");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("resolvedBy").asText()).isEqualTo("sup01");
    }

    @Test
    void unknownAlertIs404() {
        assertThat(get("/api/v1/alerts/nope", "op01").code()).isEqualTo("NOT_FOUND");
        assertThat(post("/api/v1/alerts/nope/claim", "op01", null).code()).isEqualTo("NOT_FOUND");
        assertThat(get("/api/v1/alerts/nope/audit", "op01").code()).isEqualTo("NOT_FOUND");
    }

    // ---- R5 audit trail -----------------------------------------------------------------------

    @Test
    void r5_auditTrailIsAppendOnlyInTheDatabase() {
        String id = newAlert();
        assertThatThrownBy(() -> db.sql("UPDATE alert_audit SET actor = 'x' WHERE alert_id = :id").param("id", id).update())
                .rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> db.sql("DELETE FROM alert_audit WHERE alert_id = :id").param("id", id).update())
                .rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> db.sql("TRUNCATE alert_audit CASCADE").update()).rootCause().hasMessageContaining("append-only");
    }

    @Test
    void r5_underConcurrentCommandsTheAuditReplaysToTheFinalStateAndCountsEveryChange() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(newAlert());
        }
        Random random = new Random(3);
        List<Callable<Resp>> tasks = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            String id = ids.get(random.nextInt(ids.size()));
            String user = random.nextInt(6) == 0 ? "sup01" : String.format("op%02d", 1 + random.nextInt(3));
            int op = random.nextInt(3);
            tasks.add(() -> switch (op) {
                case 0 -> post("/api/v1/alerts/" + id + "/claim", user, null);
                case 1 -> post("/api/v1/alerts/" + id + "/release", user, null);
                default -> post("/api/v1/alerts/" + id + "/resolve", user, "{\"note\":\"done\"}");
            });
        }
        List<Resp> results = parallel(tasks);
        assertThat(results).allMatch(r -> r.status() == 200 || r.status() == 403 || r.status() == 409);

        for (String id : ids) {
            Resp alert = get("/api/v1/alerts/" + id, "op01");
            var audit = get("/api/v1/alerts/" + id + "/audit", "op01").body().get("items");
            String state = null;
            for (var e : audit) {
                String from = e.get("fromStatus").isNull() ? null : e.get("fromStatus").asText();
                assertThat(from).isEqualTo(state);
                state = e.get("toStatus").asText();
            }
            assertThat(state).isEqualTo(alert.body().get("status").asText());
        }
        long changes = db.sql("SELECT count(*) FROM alert_audit WHERE alert_id IN (:ids) AND action <> 'OPENED'")
                .param("ids", ids).query(Long.class).single();
        // a 200 is either a change or an idempotent repeat; the audit never holds more entries than 200s
        long ok = results.stream().filter(r -> r.status() == 200).count();
        assertThat(changes).isPositive().isLessThanOrEqualTo(ok);
    }
}
