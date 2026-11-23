package com.zonewatch.config;

import com.zonewatch.common.ApiException;
import com.zonewatch.common.ProblemCode;
import com.zonewatch.common.Problems;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps every failure to problem+json with a code from the catalogue. Responses never carry
 * stack traces, SQL or internal names; the full exception goes to the log with the request id.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> api(ApiException e, HttpServletRequest request) {
        return Problems.response(e.code(), e.getMessage(), request);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, Object>> badParameter(Exception e, HttpServletRequest request) {
        String detail = e instanceof MissingServletRequestParameterException m
                ? "parameter '" + m.getParameterName() + "' is required"
                : "parameter '" + ((MethodArgumentTypeMismatchException) e).getName() + "' has an invalid value";
        return Problems.response(ProblemCode.VALIDATION_FAILED, detail, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadable(HttpServletRequest request) {
        return Problems.response(ProblemCode.VALIDATION_FAILED, "request body is not valid JSON", request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Map<String, Object>> mediaType(HttpMediaTypeNotSupportedException e, HttpServletRequest request) {
        return Problems.response(ProblemCode.UNSUPPORTED_MEDIA_TYPE, "content type is not supported", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Map<String, Object>> method(HttpServletRequest request) {
        return Problems.response(ProblemCode.NOT_FOUND, "no such operation", request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Map<String, Object>> noResource(HttpServletRequest request) {
        return Problems.response(ProblemCode.NOT_FOUND, "no such resource", request);
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    ResponseEntity<Map<String, Object>> denied(HttpServletRequest request) {
        return Problems.response(ProblemCode.FORBIDDEN, "your role may not call this operation", request);
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    void clientGone() {
        // the client of a live stream went away; nothing to answer
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception e, HttpServletRequest request) {
        log.error("unhandled error on {} {}", request.getMethod(), request.getRequestURI(), e);
        return Problems.response(ProblemCode.INTERNAL_ERROR, "the request could not be completed", request);
    }
}
