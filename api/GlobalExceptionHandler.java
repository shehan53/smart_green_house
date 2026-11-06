package com.greenhouse.api;

import com.greenhouse.kernel.UnknownSensorException;
import com.greenhouse.monitor.Log;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Author: Role 2 - Fault Tolerance Engineer
 * Concepts: #5 Exception handling - every bad request is answered with a clear error and logged,
 *           the real-time system is never affected and never crashes
 * Purpose: Turns invalid inputs (out of range, wrong type, broken JSON, unknown sensor, unknown URL)
 *          into 400 / 404 / 405 / 415 responses. Unexpected errors become 500 but the system keeps running.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalidField(MethodArgumentNotValidException e, HttpServletRequest req) {
        List<String> details = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage() + " (got " + f.getRejectedValue() + ")")
                .toList();
        return reject(HttpStatus.BAD_REQUEST, "Validation failed", details, req);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e, HttpServletRequest req) {
        return reject(HttpStatus.BAD_REQUEST, "Malformed JSON or wrong data type", List.of(rootMessage(e)), req);
    }

    @ExceptionHandler(UnknownSensorException.class)
    public ResponseEntity<Map<String, Object>> unknownSensor(UnknownSensorException e, HttpServletRequest req) {
        return reject(HttpStatus.NOT_FOUND, e.getMessage(), List.of(), req);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badArgument(IllegalArgumentException e, HttpServletRequest req) {
        return reject(HttpStatus.BAD_REQUEST, e.getMessage(), List.of(), req);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> noEndpoint(NoResourceFoundException e, HttpServletRequest req) {
        return reject(HttpStatus.NOT_FOUND, "No such endpoint", List.of(), req);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> wrongMethod(HttpRequestMethodNotSupportedException e, HttpServletRequest req) {
        return reject(HttpStatus.METHOD_NOT_ALLOWED, e.getMessage(), List.of(), req);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> wrongMediaType(HttpMediaTypeNotSupportedException e, HttpServletRequest req) {
        return reject(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Content-Type must be application/json", List.of(), req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e, HttpServletRequest req) {
        Log.error("API", "Unexpected error in " + req.getMethod() + " " + req.getRequestURI() + " - system keeps running", e);
        return reject(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error: " + e.getMessage(), List.of(), req);
    }

    private ResponseEntity<Map<String, Object>> reject(HttpStatus status, String error, List<String> details,
                                                       HttpServletRequest req) {
        Log.inputRejected("Postman", req.getMethod() + " " + req.getRequestURI(),
                status.value() + " " + error + (details.isEmpty() ? "" : " " + details));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("error", error);
        body.put("details", details);
        body.put("path", req.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null) root = root.getCause();
        String msg = root.getMessage() == null ? root.toString() : root.getMessage();
        int newline = msg.indexOf('\n');
        return newline > 0 ? msg.substring(0, newline) : msg;
    }
}
