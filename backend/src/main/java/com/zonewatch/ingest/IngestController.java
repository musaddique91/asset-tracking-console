package com.zonewatch.ingest;

import com.zonewatch.common.ApiException;
import com.zonewatch.common.ProblemCode;
import com.zonewatch.config.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/v1/reports/batch. The body is read here with hard limits (5 MB, 1,000 non-empty lines)
 * before anything is parsed; all data access happens in the service.
 */
@RestController
public class IngestController {

    static final int MAX_BYTES = 5 * 1024 * 1024;
    static final int MAX_LINES = 1000;
    static final String NDJSON_VALUE = "application/x-ndjson";

    private final IngestService ingest;

    public IngestController(IngestService ingest) {
        this.ingest = ingest;
    }

    /** Any other Content-Type is refused by Spring with 415 (mapped to UNSUPPORTED_MEDIA_TYPE). */
    @PostMapping(path = "/api/v1/reports/batch", consumes = NDJSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public BatchResult batch(HttpServletRequest request) throws IOException {
        // read at most 5 MB + 1 byte whatever Content-Length says; never buffer more than that
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readNBytes(MAX_BYTES + 1);
        }
        if (body.length > MAX_BYTES) {
            throw new ApiException(ProblemCode.PAYLOAD_TOO_LARGE, "the body is larger than 5 MB");
        }
        List<String> lines = nonEmptyLines(new String(body, StandardCharsets.UTF_8));
        if (lines.size() > MAX_LINES) {
            throw new ApiException(ProblemCode.PAYLOAD_TOO_LARGE, "more than 1,000 non-empty lines");
        }
        return ingest.ingest(lines, RequestIdFilter.current());
    }

    /** Splits on LF (CRLF tolerated); lines that are empty or only whitespace are not counted. */
    static List<String> nonEmptyLines(String body) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        int n = body.length();
        while (start <= n) {
            int end = body.indexOf('\n', start);
            if (end < 0) {
                end = n;
            }
            String line = body.substring(start, end);
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            if (!line.isBlank()) {
                lines.add(line);
            }
            start = end + 1;
        }
        return lines;
    }
}
