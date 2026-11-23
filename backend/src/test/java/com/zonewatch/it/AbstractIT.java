package com.zonewatch.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Integration tests run the real application over HTTP against a real PostgreSQL (same image as
 * production). Tests do not clean the database between each other; each uses its own asset ids.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIT {

    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16.4-alpine");

    static {
        PG.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("spring.flyway.enabled", () -> "true");
        r.add("zw.users-file", () -> Path.of("src/test/resources/test-users.json").toAbsolutePath().toString());
        r.add("zw.instance", () -> "test");
    }

    protected static final ObjectMapper JSON = new ObjectMapper();
    protected static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final Map<String, String> TOKENS = new ConcurrentHashMap<>();

    // inside Z-01 (RESTRICTED, Alpha Range) and a point outside every zone
    protected static final double IN_LON = 168.4, IN_LAT = 38.3;
    protected static final double OUT_LON = 166.0, OUT_LAT = 30.0;
    protected static final Instant T0 = Instant.parse("2026-11-02T06:00:00.000Z");

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcClient db;

    protected record Resp(int status, JsonNode body, HttpResponse<String> raw) {
        String code() {
            return body == null ? null : body.path("code").asText(null);
        }
    }

    protected static String password(String user) {
        return "test-" + user + "-pw";
    }

    protected String token(String user) {
        return TOKENS.computeIfAbsent(user, u -> {
            Resp r = send("POST", "/api/v1/auth/login", null, "application/json",
                    "{\"username\":\"" + u + "\",\"password\":\"" + password(u) + "\"}");
            if (r.status() != 200) {
                throw new IllegalStateException("login failed for " + u + ": " + r.status());
            }
            return r.body().get("accessToken").asText();
        });
    }

    protected Resp send(String method, String path, String user, String contentType, String body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(60));
            if (user != null) {
                b.header("Authorization", "Bearer " + token(user));
            }
            if (contentType != null) {
                b.header("Content-Type", contentType);
            }
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<String> res = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode json = res.body() == null || res.body().isBlank() ? null : JSON.readTree(res.body());
            return new Resp(res.statusCode(), json, res);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected Resp get(String path, String user) {
        return send("GET", path, user, null, null);
    }

    protected Resp post(String path, String user, String json) {
        return send("POST", path, user, json == null ? null : "application/json", json);
    }

    protected Resp batch(List<String> lines) {
        return send("POST", "/api/v1/reports/batch", "feed", "application/x-ndjson", String.join("\n", lines) + "\n");
    }

    /** A unique asset id per test, valid against the assetId pattern. */
    protected static String asset() {
        return "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
    }

    protected static String rid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    protected static String report(String reportId, String assetId, Instant observedAt, double lon, double lat) {
        return "{\"reportId\":\"" + reportId + "\",\"assetId\":\"" + assetId + "\",\"kind\":\"SURFACE\",\"observedAt\":\""
                + observedAt + "\",\"lat\":" + lat + ",\"lon\":" + lon + ",\"speedKn\":10.0,\"headingDeg\":90.0,\"source\":\"SAT-1\"}";
    }

    protected long activeAlerts(String assetId) {
        return db.sql("SELECT count(*) FROM alert WHERE asset_id = :a AND status IN ('OPEN','CLAIMED')")
                .param("a", assetId).query(Long.class).single();
    }

    protected long alerts(String assetId) {
        return db.sql("SELECT count(*) FROM alert WHERE asset_id = :a").param("a", assetId).query(Long.class).single();
    }

    protected String currentReport(String assetId) {
        return db.sql("SELECT report_id FROM asset_position WHERE asset_id = :a").param("a", assetId)
                .query(String.class).optional().orElse(null);
    }

    protected long trackSize(String assetId) {
        return db.sql("SELECT count(*) FROM report WHERE asset_id = :a").param("a", assetId).query(Long.class).single();
    }

    /** Opens one alert for a fresh asset in Z-01 and returns its id. */
    protected String newAlert() {
        String a = asset();
        batch(List.of(report(rid(), a, T0, IN_LON, IN_LAT)));
        return db.sql("SELECT alert_id FROM alert WHERE asset_id = :a").param("a", a).query(String.class).single();
    }

    /** Runs all tasks at the same moment on separate threads and returns their results. */
    protected static <T> List<T> parallel(List<Callable<T>> tasks) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(tasks.size())) {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = tasks.stream().map(t -> pool.submit(() -> {
                ready.countDown();
                go.await();
                return t.call();
            })).collect(Collectors.toList());
            ready.await();
            go.countDown();
            List<T> results = new java.util.ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        }
    }
}
