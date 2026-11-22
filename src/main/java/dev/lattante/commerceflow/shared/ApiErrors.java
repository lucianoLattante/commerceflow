package dev.lattante.commerceflow.shared;

import dev.lattante.commerceflow.identity.Unauthorized;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiErrors {
  public static ProblemDetail problem(HttpStatus status, String code, String message) {
    var p = ProblemDetail.forStatusAndDetail(status, message);
    p.setProperty("code", code);
    return p;
  }

  @ExceptionHandler(dev.lattante.commerceflow.inventory.InsufficientStock.class)
  ProblemDetail stockConflict() {
    return problem(HttpStatus.CONFLICT, "insufficient_stock", "Insufficient available stock");
  }

  @ExceptionHandler(dev.lattante.commerceflow.orders.IdempotencyConflict.class)
  ProblemDetail idempotencyConflict() {
    return problem(
        HttpStatus.CONFLICT,
        "idempotency_conflict",
        "Idempotency-Key was already used for a different request");
  }

  @ExceptionHandler(dev.lattante.commerceflow.orders.OrderStateConflict.class)
  ProblemDetail orderState() {
    return problem(
        HttpStatus.CONFLICT,
        "order_state_conflict",
        "Operation is incompatible with the current order state");
  }

  @ExceptionHandler(org.springframework.dao.TransientDataAccessException.class)
  ResponseEntity<ProblemDetail> databaseBusy() {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .header("Retry-After", "1")
        .body(
            problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "database_busy",
                "Retry according to the endpoint idempotency or version contract"));
  }

  @ExceptionHandler(dev.lattante.commerceflow.messaging.MessageFailure.Conflict.class)
  ProblemDetail messageState() {
    return problem(
        HttpStatus.CONFLICT, "message_state_conflict", "Message cannot be retried or replayed");
  }

  @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
  ProblemDetail accessDenied() {
    return problem(HttpStatus.FORBIDDEN, "forbidden", "Operation is not permitted");
  }

  @ExceptionHandler(dev.lattante.commerceflow.catalog.ProductConflict.class)
  ProblemDetail productConflict(dev.lattante.commerceflow.catalog.ProductConflict e) {
    return problem(HttpStatus.CONFLICT, "product_conflict", e.getMessage());
  }

  @ExceptionHandler(dev.lattante.commerceflow.identity.UserStateConflict.class)
  ProblemDetail userStateConflict(dev.lattante.commerceflow.identity.UserStateConflict e) {
    return problem(HttpStatus.CONFLICT, "user_state_conflict", e.getMessage());
  }

  @ExceptionHandler(DomainViolation.class)
  ProblemDetail domain(DomainViolation e) {
    return problem(HttpStatus.BAD_REQUEST, "invalid_request", e.getMessage());
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
  })
  ProblemDetail validation(MethodArgumentNotValidException e) {
    var fields = e.getBindingResult().getFieldErrors().stream()
        .map(org.springframework.validation.FieldError::getField).distinct().limit(10).toList();
    return problem(HttpStatus.BAD_REQUEST, "invalid_request",
        fields.isEmpty() ? "Request validation failed" : "Request validation failed for: " + String.join(", ", fields));
  }

  @ExceptionHandler(org.springframework.web.bind.MissingRequestHeaderException.class)
  ProblemDetail missingHeader(org.springframework.web.bind.MissingRequestHeaderException e) {
    return problem(HttpStatus.BAD_REQUEST, "invalid_request", "Required header is missing: " + e.getHeaderName());
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ProblemDetail invalidParameter(MethodArgumentTypeMismatchException e) {
    return problem(HttpStatus.BAD_REQUEST, "invalid_request",
        e.getRequiredType() == java.util.UUID.class
            ? "Parameter '" + e.getName() + "' must be a valid resource UUID"
            : "Request parameter has an invalid value: " + e.getName());
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ProblemDetail invalidBody(HttpMessageNotReadableException e) {
    boolean invalidDecision = e.getCause() instanceof com.fasterxml.jackson.databind.exc.InvalidFormatException invalid
        && invalid.getTargetType() == dev.lattante.commerceflow.orders.PaymentDecision.class;
    return problem(HttpStatus.BAD_REQUEST, "invalid_request",
        invalidDecision ? "decision must be APPROVED or DECLINED" : "Request body must contain valid JSON with valid field values");
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ProblemDetail conflict() {
    return problem(HttpStatus.CONFLICT, "conflict", "Resource conflicts with an existing record");
  }

  @ExceptionHandler(NotFound.class)
  ProblemDetail missing() {
    return problem(HttpStatus.NOT_FOUND, "not_found", "Resource not found");
  }

  @ExceptionHandler(Unauthorized.class)
  ProblemDetail unauthorized() {
    return problem(HttpStatus.UNAUTHORIZED, "unauthorized", "Authentication required or invalid");
  }

  @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
  ProblemDetail methodNotAllowed() {
    return problem(
        HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed", "HTTP method is not supported");
  }

  @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
  ProblemDetail resourceNotFound() {
    return missing();
  }

  @ExceptionHandler(Exception.class)
  ProblemDetail unexpected(Exception e) {
    org.slf4j.LoggerFactory.getLogger(ApiErrors.class)
        .error("Unexpected request failure: {}", e.getClass().getName());
    return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Unexpected server error");
  }
}
