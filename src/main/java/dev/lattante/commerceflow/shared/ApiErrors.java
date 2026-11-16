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

  @ExceptionHandler(DomainViolation.class)
  ProblemDetail domain(DomainViolation e) {
    return problem(HttpStatus.BAD_REQUEST, "invalid_request", e.getMessage());
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class
  })
  ProblemDetail validation(Exception e) {
    return problem(HttpStatus.BAD_REQUEST, "invalid_request", "Request validation failed");
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
