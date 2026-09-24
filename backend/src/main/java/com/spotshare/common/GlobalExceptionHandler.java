package com.spotshare.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.spotshare.config.CorrelationIdFilter;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Maps exceptions to the global error envelope. The correlation ID is taken
 * from the {@link CorrelationIdFilter} request attribute so error responses
 * can be traced to request logs.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorEnvelope> handleApi(ApiException ex,
                                                   HttpServletRequest request) {
        return ResponseEntity.status(ex.getStatus())
                .body(ErrorEnvelope.of(ex.getCode(), ex.getMessage(), correlationId(request)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorEnvelope> handleValidation(MethodArgumentNotValidException ex,
                                                         HttpServletRequest request) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .findFirst()
                .orElse("invalid request");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorEnvelope.of("VALIDATION_ERROR", detail, correlationId(request)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorEnvelope> handleUnexpected(Exception ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorEnvelope.of("INTERNAL_ERROR", "An unexpected error occurred.",
                        correlationId(request)));
    }

    private static String correlationId(HttpServletRequest request) {
        Object id = request.getAttribute(CorrelationIdFilter.ATTRIBUTE);
        return id == null ? null : id.toString();
    }
}
