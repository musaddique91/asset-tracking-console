package com.zonewatch.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zonewatch.tool.SourceTypeBackfill;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * CR-1 step by step on its own database, as it happens on a live v1.0.0 system:
 * V1 with v1.0.0 data -> V2 expand -> v1.0.0 keeps writing -> backfill -> V3 validate -> V4 contract,
 * and v1.0.0 can still write afterwards (the rollback path).
 */
class Cr1MigrationIT {

    static final PostgreSQLContainer<?> PG = AbstractIT.PG;
    static JdbcTemplate jdbc;
    static DriverManagerDataSource ds;

    @BeforeAll
    static void database() throws Exception {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS cr1");
            s.execute("CREATE DATABASE cr1");
        }
        ds = new DriverManagerDataSource(PG.getJdbcUrl().replaceAll("/[^/?]+(\\?|$)", "/cr1$1"), PG.getUsername(), PG.getPassword());
        jdbc = new JdbcTemplate(ds);
    }

    static Flyway flyway(String target) {
        return Flyway.configure().dataSource(ds).locations("classpath:db/migration").target(target).load();
    }

    /** How v1.0.0 writes: it does not know the column. */
    static void v100Insert(String id, String source) {
        jdbc.update("INSERT INTO report (report_id, asset_id, observed_at, lat, lon, source) VALUES (?, 'AST-1', now(), 1, 2, ?)",
                id, source);
    }

    static String sourceType(String id) {
        return jdbc.queryForObject("SELECT source_type FROM report WHERE report_id = ?", String.class, id);
    }

    @Test
    void expandBackfillValidateContract() {
        flyway("1").migrate();
        for (int i = 0; i < 5000; i++) {
            v100Insert("old-" + i, switch (i % 5) {
                case 0 -> "SAT-1";
                case 1 -> "GND-2";
                case 2 -> "MOB-1";
                case 3 -> "XYZ-9";
                default -> null;
            });
        }

        // V2: column added, existing rows untouched (NULL)
        flyway("2").migrate();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report WHERE source_type IS NULL", Long.class)).isEqualTo(5000);

        // v1.0.0 still running: its inserts get the value from the trigger
        v100Insert("mixed-1", "GND-1");
        assertThat(sourceType("mixed-1")).isEqualTo("GROUND");
        // a writer that sets NULL explicitly is refused by the NOT VALID check (it applies to new rows)
        assertThatThrownBy(() -> jdbc.update("ALTER TABLE report DISABLE TRIGGER report_fill_source_type; "
                + "INSERT INTO report (report_id, asset_id, observed_at, lat, lon, source_type) VALUES ('bad', 'AST-1', now(), 1, 2, NULL)"))
                .hasMessageContaining("report_source_type_not_null");
        jdbc.update("ALTER TABLE report ENABLE TRIGGER report_fill_source_type");

        // V3 before the backfill fails and changes nothing
        assertThatThrownBy(() -> flyway("3").migrate()).hasMessageContaining("report_source_type_not_null");
        jdbc.update("DELETE FROM flyway_schema_history WHERE success = false");

        // backfill, in small batches, twice (idempotent)
        var backfill = new SourceTypeBackfill(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)));
        var first = backfill.run(700, Duration.ZERO, "");
        assertThat(first.updated()).isEqualTo(5000);
        assertThat(backfill.run(700, Duration.ZERO, "").updated()).isZero();
        assertThat(sourceType("old-0")).isEqualTo("SATELLITE");
        assertThat(sourceType("old-3")).isEqualTo("UNKNOWN");
        assertThat(sourceType("old-4")).isEqualTo("UNKNOWN");

        // V3 + V4
        flyway("latest").migrate();
        assertThat(jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns WHERE table_name = 'report' AND column_name = 'source_type'",
                String.class)).isEqualTo("NO");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conrelid = 'report'::regclass AND NOT convalidated",
                Long.class)).isZero();

        // rollback path: a v1.0.0 instance can still write after the last step
        v100Insert("after-1", "MOB-2");
        assertThat(sourceType("after-1")).isEqualTo("MOBILE");
    }
}
