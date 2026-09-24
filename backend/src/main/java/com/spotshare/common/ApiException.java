package com.spotshare.common;

import org.springframework.http.HttpStatus;

/**
 * A domain error that maps directly to an HTTP status and a stable,
 * user-facing error code. Thrown from services; translated to the global
 * error envelope by {@link GlobalExceptionHandler}. Messages are written for
 * people, never for stack traces.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException unauthorized(String code, String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, code, message);
    }

    public static ApiException forbidden(String code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
