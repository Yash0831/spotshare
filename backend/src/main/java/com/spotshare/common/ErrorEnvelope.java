package com.spotshare.common;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Global error envelope: every error response is shaped as
 * {@code {"code":"...","message":"...","correlationId":"..."}}.
 *
 * <p>Friendly messages only — never stack traces, SQL text, or internal
 * class names. The correlation ID ties the response to the request log line.
 * Some errors add a {@code details} object with machine-readable extras
 * (e.g. {@code earliestReturnTime} when a return-early is blocked by a
 * reservation); it is omitted when empty.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorEnvelope(String code, String message, String correlationId,
                            Map<String, Object> details) {

    public static ErrorEnvelope of(String code, String message, String correlationId) {
        return new ErrorEnvelope(code, message, correlationId, null);
    }

    public static ErrorEnvelope of(String code, String message, String correlationId,
                                   Map<String, Object> details) {
        return new ErrorEnvelope(code, message, correlationId, details);
    }
}
