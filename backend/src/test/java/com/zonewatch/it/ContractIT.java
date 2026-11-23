package com.zonewatch.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Shapes, codes and conventions of the fixed contract. */
class ContractIT extends AbstractIT {

    private static final String TIME = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z";

    @Test
    void healthIsPublic() {
        Resp r = get("/health", null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("status").asText()).isEqualTo("UP");
    }

    @Test
    void loginAnswersTheContractShape() {
        Resp r = send("POST", "/api/v1/auth/login", null, "application/json",
                "{\"username\":\"op01\",\"password\":\"" + password("op01") + "\"}");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(r.body().get("expiresInSec").asLong()).isPositive();
        assertThat(r.body().get("user").get("username").asText()).isEqualTo("op01");
        assertThat(r.body().get("user").get("role").asText()).isEqualTo("OPERATOR");
        assertThat(r.body().get("accessToken").asText()).isNotBlank();
    }

    @Test
    void wrongPasswordIs401Problem() {
        Resp r = send("POST", "/api/v1/auth/login", null, "application/json", "{\"username\":\"op01\",\"password\":\"nope\"}");
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.code()).isEqualTo("UNAUTHENTICATED");
        assertThat(r.raw().headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
    }

    @Test
    void rolesAreEnforced() {
        assertThat(get("/api/v1/assets/X", null).code()).isEqualTo("UNAUTHENTICATED");
        assertThat(get("/api/v1/alerts", "feed").code()).isEqualTo("FORBIDDEN");
        assertThat(send("POST", "/api/v1/reports/batch", "op01", "application/x-ndjson", "{}\n").code()).isEqualTo("FORBIDDEN");
        assertThat(get("/api/v1/stats", "feed").status()).isEqualTo(200);
        assertThat(get("/api/v1/stats", "op01").status()).isEqualTo(200);
        assertThat(get("/api/v1/assets?bbox=0,0,1,1", "sup01").status()).isEqualTo(200);
        assertThat(send("GET", "/api/v1/stats", null, null, null).raw().headers().firstValue("Authorization")).isEmpty();
        Resp bad = send("GET", "/api/v1/stats", null, null, null);
        assertThat(bad.status()).isEqualTo(401);
    }

    @Test
    void batchMediaTypeAndSizeLimits() {
        assertThat(send("POST", "/api/v1/reports/batch", "feed", "application/json", "{}").code())
                .isEqualTo("UNSUPPORTED_MEDIA_TYPE");
        assertThat(send("POST", "/api/v1/reports/batch", "feed", null, "{}\n").code()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
        assertThat(send("POST", "/api/v1/reports/batch", "feed", "not a media type", "{}\n").code())
                .isEqualTo("UNSUPPORTED_MEDIA_TYPE");
        assertThat(send("POST", "/api/v1/reports/batch", "feed", "application/x-ndjson; charset=utf-8", "\"x\"\n").status())
                .isEqualTo(200);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            lines.add("{}");
        }
        assertThat(batch(lines).code()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(send("POST", "/api/v1/reports/batch", "feed", "application/x-ndjson", "x".repeat(5 * 1024 * 1024 + 10))
                .code()).isEqualTo("PAYLOAD_TOO_LARGE");
        Resp thousand = batch(lines.subList(0, 1000));
        assertThat(thousand.status()).isEqualTo(200);
        assertThat(thousand.body().get("rejected").asInt()).isEqualTo(1000);
    }

    @Test
    void requestIdIsEchoed() {
        var r = send("GET", "/health", null, null, null);
        assertThat(r.raw().headers().firstValue("X-Request-Id")).isPresent();
    }

    @Test
    void statsShape() {
        JsonNode s = get("/api/v1/stats", "op01").body();
        for (String f : List.of("received", "accepted", "duplicates", "rejected", "assets")) {
            assertThat(s.get(f).isIntegralNumber()).as(f).isTrue();
        }
        for (String f : List.of("open", "claimed", "resolved")) {
            assertThat(s.get("alerts").get(f).isIntegralNumber()).as(f).isTrue();
        }
    }

    @Test
    void statsCountEveryLineOnce() {
        JsonNode before = get("/api/v1/stats", "feed").body();
        String a = asset();
        batch(List.of(report(a + "-1", a, T0, OUT_LON, OUT_LAT), report(a + "-1", a, T0, OUT_LON, OUT_LAT), "bad"));
        JsonNode after = get("/api/v1/stats", "feed").body();
        // other tests may run in between only in this JVM sequentially, so deltas are exact
        assertThat(after.get("received").asLong() - before.get("received").asLong()).isEqualTo(3);
        assertThat(after.get("accepted").asLong() - before.get("accepted").asLong()).isEqualTo(1);
        assertThat(after.get("duplicates").asLong() - before.get("duplicates").asLong()).isEqualTo(1);
        assertThat(after.get("rejected").asLong() - before.get("rejected").asLong()).isEqualTo(1);
        assertThat(after.get("assets").asLong() - before.get("assets").asLong()).isEqualTo(1);
    }

    @Test
    void assetsInABoxIncludingOneAcrossTheAntimeridian() {
        String east = asset();
        String west = asset();
        batch(List.of(report(east + "-1", east, T0, 179.9, -60.5), report(west + "-1", west, T0, -179.9, -60.5)));
        Resp crossing = get("/api/v1/assets?bbox=179.5,-61,-179.5,-60&limit=5000", "op01");
        List<String> ids = crossing.body().get("items").findValuesAsText("assetId");
        assertThat(ids).contains(east, west);
        Resp normal = get("/api/v1/assets?bbox=179.5,-61,180,-60", "op01");
        assertThat(normal.body().get("items").findValuesAsText("assetId")).contains(east).doesNotContain(west);
        JsonNode item = normal.body().get("items").get(0);
        assertThat(item.get("observedAt").asText()).matches(TIME);
        assertThat(normal.body().get("truncated").asBoolean()).isFalse();
    }

    @Test
    void assetsAreOrderedAndTruncated() {
        for (int i = 0; i < 3; i++) {
            String a = asset();
            batch(List.of(report(a + "-1", a, T0, -100.5, -50.5)));
        }
        Resp r = get("/api/v1/assets?bbox=-101,-51,-100,-50&limit=2", "op01");
        assertThat(r.body().get("items")).hasSize(2);
        assertThat(r.body().get("truncated").asBoolean()).isTrue();
        List<String> ids = r.body().get("items").findValuesAsText("assetId");
        assertThat(ids).isSorted();
    }

    @Test
    void invalidListParametersAre400() {
        assertThat(get("/api/v1/assets?bbox=1,2,3", "op01").code()).isEqualTo("VALIDATION_FAILED");
        assertThat(get("/api/v1/assets", "op01").code()).isEqualTo("VALIDATION_FAILED");
        assertThat(get("/api/v1/assets?bbox=0,0,1,1&limit=0", "op01").code()).isEqualTo("VALIDATION_FAILED");
        assertThat(get("/api/v1/assets?bbox=0,0,1,1&limit=5001", "op01").code()).isEqualTo("VALIDATION_FAILED");
        assertThat(get("/api/v1/alerts?limit=501", "op01").code()).isEqualTo("VALIDATION_FAILED");
        assertThat(get("/api/v1/alerts?status=NEW", "op01").code()).isEqualTo("VALIDATION_FAILED");
        assertThat(get("/api/v1/alerts?cursor=not-a-cursor", "op01").code()).isEqualTo("VALIDATION_FAILED");
        assertThat(get("/api/v1/assets/NOPE-1/track?limit=abc", "op01").code()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void assetAndTrack() {
        String a = asset();
        batch(List.of(
                report(a + "-3", a, T0.plusSeconds(3), OUT_LON, OUT_LAT),
                report(a + "-1", a, T0.plusSeconds(1), OUT_LON, OUT_LAT),
                report(a + "-2", a, T0.plusSeconds(2), OUT_LON, OUT_LAT)));
        Resp current = get("/api/v1/assets/" + a, "op01");
        assertThat(current.body().get("reportId").asText()).isEqualTo(a + "-3");
        assertThat(current.body().get("observedAt").asText()).isEqualTo("2026-11-02T06:00:03.000Z");

        Resp track = get("/api/v1/assets/" + a + "/track", "op01");
        assertThat(track.body().get("items").findValuesAsText("reportId")).containsExactly(a + "-1", a + "-2", a + "-3");
        Resp limited = get("/api/v1/assets/" + a + "/track?limit=2&from=2026-11-02T06:00:01.500Z", "op01");
        assertThat(limited.body().get("items").findValuesAsText("reportId")).containsExactly(a + "-2", a + "-3");
        Resp truncated = get("/api/v1/assets/" + a + "/track?limit=1", "op01");
        assertThat(truncated.body().get("truncated").asBoolean()).isTrue();
        assertThat(get("/api/v1/assets/NOPE-404", "op01").code()).isEqualTo("NOT_FOUND");
        assertThat(get("/api/v1/assets/NOPE-404/track", "op01").code()).isEqualTo("NOT_FOUND");
    }

    @Test
    void alertPagingReturnsEveryAlertExactlyOnce() {
        Set<String> created = new HashSet<>();
        for (int i = 0; i < 7; i++) {
            created.add(newAlert());
        }
        Set<String> seen = new HashSet<>();
        String cursor = null;
        int pages = 0;
        do {
            Resp page = get("/api/v1/alerts?limit=3" + (cursor == null ? "" : "&cursor=" + cursor), "op01");
            for (JsonNode a : page.body().get("items")) {
                assertThat(seen.add(a.get("alertId").asText())).isTrue();
                assertThat(a.get("openedAt").asText()).matches(TIME);
            }
            cursor = page.body().get("nextCursor").isNull() ? null : page.body().get("nextCursor").asText();
            pages++;
        } while (cursor != null && pages < 10000);
        assertThat(seen).containsAll(created);
    }

    @Test
    void alertFilters() {
        String id = newAlert();
        String a = get("/api/v1/alerts/" + id, "op01").body().get("assetId").asText();
        Resp byAsset = get("/api/v1/alerts?assetId=" + a, "op01");
        assertThat(byAsset.body().get("items")).hasSize(1);
        assertThat(byAsset.body().get("items").get(0).get("zoneId").asText()).isEqualTo("Z-01");
        assertThat(get("/api/v1/alerts?assetId=" + a + "&status=RESOLVED", "op01").body().get("items")).isEmpty();
        assertThat(get("/api/v1/alerts?assetId=" + a + "&zoneId=Z-01&status=OPEN", "op01").body().get("items")).hasSize(1);
    }

    @Test
    void zonesAndOccupancyForTheConsole() {
        String a = asset();
        batch(List.of(report(a + "-1", a, T0, IN_LON, IN_LAT)));
        Resp zones = get("/api/v1/zones", "op01");
        assertThat(zones.body().get("items")).hasSize(12);
        Resp occ = get("/api/v1/zones/occupancy", "op01");
        JsonNode z01 = null;
        for (JsonNode z : occ.body().get("items")) {
            if (z.get("zoneId").asText().equals("Z-01")) {
                z01 = z;
            }
        }
        assertThat(z01).isNotNull();
        assertThat(z01.get("assets").findValuesAsText("assetId")).contains(a);
    }
}
