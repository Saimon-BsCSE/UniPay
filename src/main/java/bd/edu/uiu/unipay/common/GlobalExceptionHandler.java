package bd.edu.uiu.unipay.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translates every exception into a consistent JSON envelope:
 * {@code {"status": 409, "message": "...", "timestamp": "..."}}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException ex) {
        return body(ex.getStatus(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .orElse("Request validation failed");
        return body(HttpStatus.BAD_REQUEST.value(), message);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return body(HttpStatus.CONFLICT.value(),
                "Operation conflicts with existing data (duplicate ID, phone number or transaction).");
    }

    /** Method-level RBAC denials (@PreAuthorize) must surface as 403, not 500. */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(
            org.springframework.security.access.AccessDeniedException ex) {
        return body(HttpStatus.FORBIDDEN.value(), "You do not have permission to perform this action.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        log.error("Unhandled server error", ex);
        return body(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Something went wrong. Please try again.");
    }

    private ResponseEntity<Map<String, Object>> body(int status, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status);
        payload.put("message", message);
        payload.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(status).body(payload);
    }
}
