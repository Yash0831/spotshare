package com.spotshare.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Global error envelope: every error response is shaped as
 * {@code {"code":"...","message":"...","correlationId":"..."}}.
 *
 * <p>Friendly messages only — never stack traces, SQL text, or internal
 * class names. The correlation ID ties the response to the request log line.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorEnvelope(String code, String message, String correlationId) {

    public static ErrorEnvelope of(String code, String message, String correlationId) {
        return new ErrorEnvelope(code, message, correlationId);
    }
}
