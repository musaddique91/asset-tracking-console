package com.zonewatch.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Builds problem+json bodies. Used by the exception handler and by the security filters. */
public final class Problems {

    public static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    private Problems() {
    }

    public static Map<String, Object> body(ProblemCode code, String detail, String instance) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", code.title());
        body.put("status", code.status().value());
        body.put("code", code.name());
        if (detail != null) {
            body.put("detail", detail);
        }
        if (instance != null) {
            body.put("instance", instance);
        }
        return body;
    }

    public static ResponseEntity<Map<String, Object>> response(ProblemCode code, String detail, HttpServletRequest request) {
        return ResponseEntity.status(code.status())
                .contentType(PROBLEM_JSON)
                .body(body(code, detail, request == null ? null : request.getRequestURI()));
    }

    public static void write(HttpServletResponse response, ObjectMapper mapper, ProblemCode code, String detail,
                             HttpServletRequest request) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(PROBLEM_JSON.toString());
        mapper.writeValue(response.getOutputStream(), body(code, detail, request.getRequestURI()));
    }
}
