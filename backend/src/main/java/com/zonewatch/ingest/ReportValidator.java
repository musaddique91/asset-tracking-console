package com.zonewatch.ingest;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * Judges one line on its own (R6). Checks run in the order of the contract's rejection codes, so a
 * line with several problems always gets the same, first applicable code.
 *
 * Decisions beyond the contract (see README, data findings):
 * - observedAt must carry a zone (Z or an offset); a local time without zone is not an instant.
 *   Offsets are converted to UTC; the instant itself is never changed.
 * - observedAt more than maxFutureSkew ahead of the server clock is rejected: such a report would
 *   become the current position for years and hide every real report of the asset.
 * - Numbers must be JSON numbers; "35.1" (a string) is not a number.
 * - Optional fields, when present and not null, must have their contract type; empty strings for
 *   kind and source are stored as absent.
 */
public class ReportValidator {

    static final Pattern ASSET_ID = Pattern.compile("^[A-Z0-9][A-Z0-9-]{2,31}$");
    private static final int MAX_REPORT_ID = 64;
    private static final int MAX_TEXT = 64;
    private static final Instant EARLIEST = Instant.parse("1900-01-01T00:00:00Z");

    private final ObjectReader reader;
    private final Clock clock;
    private final Duration maxFutureSkew;

    public ReportValidator(Clock clock, Duration maxFutureSkew) {
        ObjectMapper strict = JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .build();
        this.reader = strict.reader();
        this.clock = clock;
        this.maxFutureSkew = maxFutureSkew;
    }

    /** Returns a Report or a Rejection. */
    public Object validate(int line, String raw) {
        JsonNode node;
        try {
            node = reader.readTree(raw);
        } catch (Exception e) {
            return reject(line, RejectionCode.MALFORMED_JSON, "the line is not valid JSON", raw);
        }
        if (node == null || !node.isObject()) {
            return reject(line, RejectionCode.MALFORMED_JSON, "the line is not a JSON object", raw);
        }
        for (String field : new String[] {"reportId", "assetId", "observedAt", "lat", "lon"}) {
            JsonNode v = node.get(field);
            if (v == null || v.isNull() || (v.isTextual() && v.textValue().isEmpty())) {
                return reject(line, RejectionCode.MISSING_FIELD, field + " is missing", raw);
            }
        }
        JsonNode lat = node.get("lat");
        JsonNode lon = node.get("lon");
        if (!lat.isNumber() || !lon.isNumber()) {
            return reject(line, RejectionCode.INVALID_COORDINATE, (lat.isNumber() ? "lon" : "lat") + " is not a number", raw);
        }
        double latV = lat.doubleValue();
        double lonV = lon.doubleValue();
        if (!Double.isFinite(latV) || latV < -90 || latV > 90) {
            return reject(line, RejectionCode.INVALID_COORDINATE, "lat is out of range -90..90", raw);
        }
        if (!Double.isFinite(lonV) || lonV < -180 || lonV > 180) {
            return reject(line, RejectionCode.INVALID_COORDINATE, "lon is out of range -180..180", raw);
        }
        JsonNode observed = node.get("observedAt");
        if (!observed.isTextual()) {
            return reject(line, RejectionCode.INVALID_TIMESTAMP, "observedAt must be an ISO-8601 string", raw);
        }
        Instant observedAt;
        try {
            observedAt = OffsetDateTime.parse(observed.textValue(), DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            return reject(line, RejectionCode.INVALID_TIMESTAMP, "observedAt is not an ISO-8601 instant with zone", raw);
        }
        if (observedAt.isBefore(EARLIEST)) {
            return reject(line, RejectionCode.INVALID_TIMESTAMP, "observedAt is before 1900", raw);
        }
        if (observedAt.isAfter(clock.instant().plus(maxFutureSkew))) {
            return reject(line, RejectionCode.INVALID_TIMESTAMP, "observedAt is in the future", raw);
        }
        JsonNode reportId = node.get("reportId");
        if (!reportId.isTextual() || reportId.textValue().isBlank()) {
            return reject(line, RejectionCode.INVALID_FIELD, "reportId must be a non-blank string", raw);
        }
        if (reportId.textValue().indexOf('\u0000') >= 0) {
            return reject(line, RejectionCode.INVALID_FIELD, "reportId contains a NUL character", raw);
        }
        if (reportId.textValue().codePointCount(0, reportId.textValue().length()) > MAX_REPORT_ID) {
            return reject(line, RejectionCode.INVALID_FIELD, "reportId is longer than 64 characters", raw);
        }
        JsonNode assetId = node.get("assetId");
        if (!assetId.isTextual() || !ASSET_ID.matcher(assetId.textValue()).matches()) {
            return reject(line, RejectionCode.INVALID_FIELD, "assetId does not match ^[A-Z0-9][A-Z0-9-]{2,31}$", raw);
        }
        Object kind = optionalText(node, "kind");
        if (kind instanceof Rejection) {
            return reject(line, RejectionCode.INVALID_FIELD, "kind must be a string", raw);
        }
        Object source = optionalText(node, "source");
        if (source instanceof Rejection) {
            return reject(line, RejectionCode.INVALID_FIELD, "source must be a string", raw);
        }
        Object speed = optionalNumber(node, "speedKn");
        if (speed instanceof Rejection) {
            return reject(line, RejectionCode.INVALID_FIELD, "speedKn must be a number", raw);
        }
        Object heading = optionalNumber(node, "headingDeg");
        if (heading instanceof Rejection) {
            return reject(line, RejectionCode.INVALID_FIELD, "headingDeg must be a number", raw);
        }
        return new Report(line, reportId.textValue(), assetId.textValue(), (String) kind, observedAt, latV, lonV,
                (Double) speed, (Double) heading, (String) source, raw);
    }

    private static final Rejection BAD = new Rejection(0, RejectionCode.INVALID_FIELD, null, null);

    private static Object optionalText(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isTextual() || v.textValue().length() > MAX_TEXT || v.textValue().indexOf('\u0000') >= 0) {
            return BAD;
        }
        return v.textValue().isEmpty() ? null : v.textValue();
    }

    private static Object optionalNumber(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isNumber() || !Double.isFinite(v.doubleValue())) {
            return BAD;
        }
        return v.doubleValue();
    }

    private static Rejection reject(int line, RejectionCode code, String detail, String raw) {
        return new Rejection(line, code, detail, raw);
    }
}
