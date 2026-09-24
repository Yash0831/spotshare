package com.spotshare.common;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

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

    /** A photo bigger than the servlet multipart cap — friendly 413, not a 500. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorEnvelope> handleMaxUpload(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ErrorEnvelope.of("PHOTO_TOO_LARGE",
                        "That photo is too large. Please use a photo under 5 MB.",
                        correlationId(request)));
    }

    /**
     * A required query/path parameter is missing (e.g. /geocode without q)
     * — friendly 400, not a 500.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorEnvelope> handleMissingParam(
            MissingServletRequestParameterException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorEnvelope.of("VALIDATION_ERROR",
                        "Missing required parameter: " + ex.getParameterName() + ".",
                        correlationId(request)));
    }

    /**
     * A query/path parameter can't be converted (e.g. ?lat=abc) — friendly
     * 400, not a 500.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorEnvelope> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorEnvelope.of("VALIDATION_ERROR",
                        "Invalid value for parameter: " + ex.getName() + ".",
                        correlationId(request)));
    }

    /** Photo upload without the "photo" part. */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErrorEnvelope> handleMissingPart(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ErrorEnvelope.of("INVALID_PHOTO",
                        "Choose a photo to upload.",
                        correlationId(request)));
    }

    /**
     * Safety net for the reservation anti-double-booking backstop: if an
     * exclusion violation (SQLState 23P01) ever escapes the booking service,
     * it still becomes the friendly 409 — never raw SQL. In this schema an
     * exclusion violation can only mean a double booking.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorEnvelope> handleDataIntegrity(
            DataIntegrityViolationException ex, HttpServletRequest request) {
        if ("23P01".equals(sqlStateOf(ex))) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ErrorEnvelope.of("SPACE_JUST_RESERVED",
                            "This space was just reserved. Please choose another nearby space.",
                            correlationId(request)));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorEnvelope.of("INTERNAL_ERROR", "An unexpected error occurred.",
                        correlationId(request)));
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

    /** Walks the cause chain for the PostgreSQL SQLState (23P01, 23505, …). */
    private static String sqlStateOf(DataIntegrityViolationException ex) {
        Throwable t = ex;
        while (t != null) {
            if (t instanceof java.sql.SQLException sql) {
                return sql.getSQLState();
            }
            t = t.getCause();
        }
        return null;
    }
}
