package com.zonewatch.common;

import org.springframework.http.HttpStatus;

/** The problem codes of the contract (openapi.yaml). Nothing else is ever returned as a code. */
public enum ProblemCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Unauthenticated"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Forbidden"),
    NOT_ALERT_OWNER(HttpStatus.FORBIDDEN, "Not the owner of the alert"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Not found"),
    ALERT_ALREADY_CLAIMED(HttpStatus.CONFLICT, "Alert already claimed"),
    INVALID_STATE(HttpStatus.CONFLICT, "Invalid state"),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "Payload too large"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");

    private final HttpStatus status;
    private final String title;

    ProblemCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }
}
