package com.intwfs.mintwf.tmf.common;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders failures as the TMF630 {@code Error} resource.
 */
@RestControllerAdvice
public class TmfExceptionHandler {

    @ExceptionHandler(TmfException.class)
    public ResponseEntity<Map<String, Object>> handle(TmfException e) {
        return error(e.status(), e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handle(HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, "Request body must be a JSON object");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handle(HttpMediaTypeNotSupportedException e) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, e.getMessage());
    }

    static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", String.valueOf(status.value()));
        body.put("reason", status.getReasonPhrase());
        body.put("message", message);
        body.put("status", String.valueOf(status.value()));
        body.put("@type", "Error");
        return ResponseEntity.status(status).body(body);
    }
}
