package com.ai_knowledge_assistant.ai_knowledge_assistant.exception;

// src/main/java/com/aiassistant/backend/exception/GlobalExceptionHandler.java
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import jakarta.servlet.http.HttpServletRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(
            MethodArgumentNotValidException ex,
            HttpServletRequest request) {

        List<String> errors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .toList();

        return ResponseEntity.badRequest().body(Map.of(
                "timestamp", Instant.now(),
                "status", 400,
                "error", "VALIDATION_FAILED",
                "details", errors,
                "path", request.getRequestURI(),
                "traceId", MDC.get("traceId") != null ? MDC.get("traceId") : "N/A"
        ));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handleBadCredentials(
            HttpServletRequest request) {
        return ResponseEntity.status(401).body(Map.of(
                "timestamp", Instant.now(),
                "status", 401,
                "error", "INVALID_CREDENTIALS",
                "message", "Invalid email or password",
                "path", request.getRequestURI()
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(
            IllegalArgumentException ex,
            HttpServletRequest request) {
        return ResponseEntity.status(409).body(Map.of(
                "timestamp", Instant.now(),
                "status", 409,
                "error", "CONFLICT",
                "message", ex.getMessage(),
                "path", request.getRequestURI()
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(
            Exception ex,
            HttpServletRequest request) {
        log.error("Unexpected error at {}", request.getRequestURI(), ex);
        return ResponseEntity.status(500).body(Map.of(
                "timestamp", Instant.now(),
                "status", 500,
                "error", "INTERNAL_ERROR",
                "message", "An unexpected error occurred",
                "traceId", MDC.get("traceId") != null ? MDC.get("traceId") : "N/A"
        ));
    }
}