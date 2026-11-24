package com.acme.support.admin;

import com.acme.support.shared.ConflictException;
import com.acme.support.shared.NotFoundException;
import com.acme.support.shared.UpstreamException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** Maps errors to the frozen body {"error":{"code","message"}}. Upstream details are logged, never echoed. */
@RestControllerAdvice
public class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    static Map<String, Object> body(String code, String message) {
        return Map.of("error", Map.of("code", code, "message", message));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body("VALIDATION_FAILED", e.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, Object>> tooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body("FILE_TOO_LARGE", "file exceeds 2 MiB"));
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Map<String, Object>> notFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body("NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<Map<String, Object>> conflict(ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body("CONFLICT", e.getMessage()));
    }

    @ExceptionHandler(UpstreamException.class)
    ResponseEntity<Map<String, Object>> upstream(UpstreamException e) {
        log.warn("upstream {} failed status={} transient={}", e.upstream(), e.status(), e.isTransient());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(body("UPSTREAM_UNAVAILABLE", "upstream " + e.upstream() + " unavailable"));
    }
}
