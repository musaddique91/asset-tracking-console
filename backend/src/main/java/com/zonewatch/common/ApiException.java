package com.zonewatch.common;

/** A refusal that maps 1:1 to an RFC 9457 problem response. The detail is shown to the caller. */
public class ApiException extends RuntimeException {

    private final ProblemCode code;

    public ApiException(ProblemCode code, String detail) {
        super(detail);
        this.code = code;
    }

    public ProblemCode code() {
        return code;
    }

    public static ApiException notFound(String what) {
        return new ApiException(ProblemCode.NOT_FOUND, what + " not found");
    }

    public static ApiException validation(String detail) {
        return new ApiException(ProblemCode.VALIDATION_FAILED, detail);
    }
}
