package dev.ledger.shared;

import java.util.Map;
import org.slf4j.LoggerFactory;
import org.springframework.dao.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class Errors {
  private ResponseEntity<?> error(int status, String code, String message) {
    return ResponseEntity.status(status)
        .body(
            Map.of(
                "code",
                code,
                "message",
                message,
                "correlationId",
                String.valueOf(org.slf4j.MDC.get("correlationId"))));
  }

  @ExceptionHandler(ApiException.class)
  ResponseEntity<?> api(ApiException ex) {
    return error(ex.status(), ex.code(), ex.getMessage());
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    org.springframework.web.bind.ServletRequestBindingException.class,
    org.springframework.web.multipart.MaxUploadSizeExceededException.class
  })
  ResponseEntity<?> invalid(Exception ex) {
    return error(
        400,
        "INVALID_REQUEST",
        "Check the required fields, value formats, and upload size (maximum 1 MB).");
  }

  @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
  ResponseEntity<?> denied(Exception ex) {
    return error(403, "FORBIDDEN", "Your role cannot perform this action.");
  }

  @ExceptionHandler(DuplicateKeyException.class)
  ResponseEntity<?> duplicate(Exception ex) {
    return error(409, "ALREADY_EXISTS", "This record or request already exists.");
  }

  @ExceptionHandler({
    CannotAcquireLockException.class,
    QueryTimeoutException.class,
    DataAccessResourceFailureException.class
  })
  ResponseEntity<?> retry(Exception ex) {
    return error(
        503,
        "TRANSFER_RETRYABLE",
        "Database temporarily busy. Retry the same transfer with the same key.");
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unexpected(Exception ex) {
    LoggerFactory.getLogger(Errors.class).error("Request failed", ex);
    return error(
        500,
        "INTERNAL_ERROR",
        "The request could not be completed. Check transfer status before starting another"
            + " transfer.");
  }
}
