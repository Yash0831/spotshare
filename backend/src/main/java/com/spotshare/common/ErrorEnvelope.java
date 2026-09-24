package com.spotshare.common;

/**
 * Global error envelope: every error response is shaped as
 * {@code {"error":{"code":"...","message":"...","correlationId":"..."}}}.
 */
public record ErrorEnvelope(ErrorDetail error) {

    public record ErrorDetail(String code, String message, String correlationId) {
    }

    public static ErrorEnvelope of(String code, String message, String correlationId) {
        return new ErrorEnvelope(new ErrorDetail(code, message, correlationId));
    }
}
