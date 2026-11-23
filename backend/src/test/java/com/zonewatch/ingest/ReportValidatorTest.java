package com.zonewatch.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/** Validity of a report (brief table 4) and the data decisions taken for the supplied feed. */
class ReportValidatorTest {

    private final ReportValidator validator = new ReportValidator(
            Clock.fixed(Instant.parse("2026-11-23T00:00:00Z"), ZoneOffset.UTC), Duration.ofHours(1));

    private static final String OK =
            "{\"reportId\":\"r1\",\"assetId\":\"AST-00001\",\"kind\":\"SURFACE\",\"observedAt\":\"2026-11-02T06:00:05.120Z\","
                    + "\"lat\":35.4,\"lon\":170.8,\"speedKn\":14.2,\"headingDeg\":271.4,\"source\":\"SAT-2\"}";

    @Test
    void acceptsAValidReport() {
        Object r = validator.validate(1, OK);
        assertThat(r).isInstanceOf(Report.class);
        Report report = (Report) r;
        assertThat(report.observedAt()).isEqualTo(Instant.parse("2026-11-02T06:00:05.120Z"));
        assertThat(report.speedKn()).isEqualTo(14.2);
    }

    @Test
    void acceptsAndNormalisesAnOffsetWithoutChangingTheInstant() {
        Report r = (Report) validator.validate(1, OK.replace("2026-11-02T06:00:05.120Z", "2026-11-02T10:00:05.120+04:00"));
        assertThat(r.observedAt()).isEqualTo(Instant.parse("2026-11-02T06:00:05.120Z"));
    }

    @Test
    void ignoresUnknownFieldsAndAcceptsMissingOptionalFields() {
        Object r = validator.validate(1, "{\"reportId\":\"r2\",\"assetId\":\"AST-1\",\"observedAt\":\"2026-11-02T06:00:05Z\","
                + "\"lat\":1,\"lon\":2,\"altM\":1200,\"meta\":{\"fw\":\"1.2.3\"}}");
        assertThat(r).isInstanceOf(Report.class);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "\"heartbeat\"                                   | MALFORMED_JSON",
            "{\"reportId\":\"r1\",\"assetId\":\"AST-1\"     | MALFORMED_JSON",
            "[1,2]                                           | MALFORMED_JSON",
            "{\"a\":1} trailing                              | MALFORMED_JSON",
            "{\"reportId\":\"r\",\"reportId\":\"s\"}         | MALFORMED_JSON",
            "{\"assetId\":\"AST-1\",\"observedAt\":\"2026-11-02T06:00:05Z\",\"lat\":1,\"lon\":2}                    | MISSING_FIELD",
            "{\"reportId\":null,\"assetId\":\"AST-1\",\"observedAt\":\"2026-11-02T06:00:05Z\",\"lat\":1,\"lon\":2}  | MISSING_FIELD",
            "{\"reportId\":\"r\",\"assetId\":\"\",\"observedAt\":\"2026-11-02T06:00:05Z\",\"lat\":1,\"lon\":2}      | MISSING_FIELD",
            "{\"reportId\":\"r\",\"assetId\":\"AST-1\",\"observedAt\":\"2026-11-02T06:00:05Z\",\"lon\":2}           | MISSING_FIELD",
            "{\"reportId\":\"r\",\"assetId\":\"AST-1\",\"observedAt\":\"2026-11-02T06:00:05Z\",\"lat\":\"N/A\",\"lon\":2} | INVALID_COORDINATE",
            "{\"reportId\":\"r\",\"assetId\":\"AST-1\",\"observedAt\":\"2026-11-02T06:00:05Z\",\"lat\":\"35.1\",\"lon\":2} | INVALID_COORDINATE",
            "{\"reportId\":\"r\",\"assetId\":\"AST-1\",\"observedAt\":\"2026-11-02T06:00:05Z\",\"lat\":1,\"lon\":true}    | INVALID_COORDINATE",
            "{\"reportId\":\"r\",\"assetId\":\"AST-1\",\"observedAt\":\"2026-11-02T06:00:05Z\",\"lat\":{},\"lon\":2}      | INVALID_COORDINATE",
            "{\"reportId\":\"r\",\"assetId\":\"AST-1\",\"observedAt\":\"2026-11-02T06:00:05Z\",\"lat\":210.5,\"lon\":2}   | INVALID_COORDINATE",
            "{\"reportId\":\"r\",\"assetId\":\"AST-1\",\"observedAt\":\"2026-11-02T06:00:05Z\",\"lat\":1,\"lon\":-761.1}  | INVALID_COORDINATE",
            "{\"reportId\":\"r\",\"assetId\":\"AST-1\",\"observedAt\":\"yesterday\",\"lat\":1,\"lon\":2}                  | INVALID_TIMESTAMP",
            "{\"reportId\":\"r\",\"assetId\":\"AST-1\",\"observedAt\":\"02/11/2026 06:00:05\",\"lat\":1,\"lon\":2}        | INVALID_TIMESTAMP",
            "{\"reportId\":\"r\",\"assetId\":\"AST-1\",\"observedAt\":\"2026-13-45T99:00:00Z\",\"lat\":1,\"lon\":2}       | INVALID_TIMESTAMP",
            "{\"reportId\":\"r\",\"assetId\":\"AST-1\",\"observedAt\":\"2026-11-02T06:05:25.697\",\"lat\":1,\"lon\":2}    | INVALID_TIMESTAMP",
            "{\"reportId\":\"r\",\"assetId\":\"AST-1\",\"observedAt\":1793599517306,\"lat\":1,\"lon\":2}                 | INVALID_TIMESTAMP",
            "{\"reportId\":\"r\",\"assetId\":\"AST-1\",\"observedAt\":\"2031-01-01T00:00:00.000Z\",\"lat\":1,\"lon\":2}   | INVALID_TIMESTAMP",
            "{\"reportId\":\"r\",\"assetId\":\"ast-00017\",\"observedAt\":\"2026-11-02T06:00:05Z\",\"lat\":1,\"lon\":2}   | INVALID_FIELD",
            "{\"reportId\":\"r\",\"assetId\":\"<script>alert(1)</script>\",\"observedAt\":\"2026-11-02T06:00:05Z\",\"lat\":1,\"lon\":2} | INVALID_FIELD",
            "{\"reportId\":\"r\",\"assetId\":\"AST-1'; DROP TABLE reports;--\",\"observedAt\":\"2026-11-02T06:00:05Z\",\"lat\":1,\"lon\":2} | INVALID_FIELD",
            "{\"reportId\":\"r\",\"assetId\":\"AB\",\"observedAt\":\"2026-11-02T06:00:05Z\",\"lat\":1,\"lon\":2}          | INVALID_FIELD",
            "{\"reportId\":17,\"assetId\":\"AST-1\",\"observedAt\":\"2026-11-02T06:00:05Z\",\"lat\":1,\"lon\":2}          | INVALID_FIELD",
            "{\"reportId\":\"r\",\"assetId\":\"AST-1\",\"observedAt\":\"2026-11-02T06:00:05Z\",\"lat\":1,\"lon\":2,\"speedKn\":\"fast\"} | INVALID_FIELD",
            "{\"reportId\":\"r\",\"assetId\":\"AST-1\",\"observedAt\":\"2026-11-02T06:00:05Z\",\"lat\":1,\"lon\":2,\"source\":5}        | INVALID_FIELD",
    })
    void rejects(String line, RejectionCode expected) {
        Object r = validator.validate(3, line.trim());
        assertThat(r).isInstanceOf(Rejection.class);
        assertThat(((Rejection) r).code()).isEqualTo(expected);
        assertThat(((Rejection) r).line()).isEqualTo(3);
    }

    @Test
    void reportIdLongerThan64IsInvalid() {
        String id = "x".repeat(65);
        Object r = validator.validate(1, OK.replace("\"r1\"", "\"" + id + "\""));
        assertThat(((Rejection) r).code()).isEqualTo(RejectionCode.INVALID_FIELD);
        assertThat(validator.validate(1, OK.replace("\"r1\"", "\"" + "x".repeat(64) + "\""))).isInstanceOf(Report.class);
    }

    @Test
    void boundaryCoordinatesAreValid() {
        assertThat(validator.validate(1, OK.replace("35.4", "90").replace("170.8", "-180"))).isInstanceOf(Report.class);
        assertThat(validator.validate(1, OK.replace("35.4", "-90").replace("170.8", "180"))).isInstanceOf(Report.class);
    }

    @Test
    void anOldSentinelTimestampIsAcceptedAsALateReport() {
        assertThat(validator.validate(1, OK.replace("2026-11-02T06:00:05.120Z", "1970-01-01T00:00:00.000Z")))
                .isInstanceOf(Report.class);
    }
}
