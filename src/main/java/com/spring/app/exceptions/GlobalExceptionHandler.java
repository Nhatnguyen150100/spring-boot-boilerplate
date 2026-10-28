package com.spring.app.exceptions;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.util.StringUtils;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.spring.app.common.response.BaseResponse;
import com.spring.app.common.response.ResponseBuilder;
import com.spring.app.modules.auth.exceptions.InvalidTokenException;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;

/**
 * Maps every exception that escapes a controller to the standard
 * {@link BaseResponse} JSON shape ({@code statusCode}, {@code message},
 * {@code timestamp}).
 *
 * <ul>
 * <li>Spring MVC's own exceptions (unreadable JSON, wrong method, wrong media
 * type, missing part, type mismatch, upload too large, unknown resource, ...)
 * are handled by the {@link ResponseEntityExceptionHandler} base class with
 * their proper 4xx status; {@link #handleExceptionInternal} only swaps the
 * body for a {@link BaseResponse}. Never add an {@code @ExceptionHandler} for
 * a type the base class already lists - Spring then fails at startup with an
 * "Ambiguous @ExceptionHandler method" error.</li>
 * <li>Every 5xx response carries the fixed {@link #GENERIC_SERVER_ERROR}
 * text; the real cause is logged at ERROR with its stack trace.</li>
 * <li>4xx responses are logged at WARN (401/403/429) or INFO (others) with
 * the exception type only. The exception message may contain user input or
 * SQL, so it is logged at DEBUG.</li>
 * </ul>
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  static final String GENERIC_SERVER_ERROR = "Internal server error";
  static final String INVALID_CREDENTIALS = "Invalid email or password";
  static final String AUTHENTICATION_FAILED = "Authentication failed";
  static final String ACCOUNT_NOT_ACTIVE = "Account is not active";
  static final String INVALID_TOKEN = "Invalid or expired token";
  static final String ACCESS_DENIED = "You do not have permission to access this resource";
  static final String DATA_CONFLICT = "The request conflicts with existing data";
  static final String VALIDATION_FAILED = "Validation failed";

  // ------------------------------------------------------------------ application exceptions

  @ExceptionHandler(ResourceNotFoundException.class)
  public ResponseEntity<BaseResponse<Void>> handleNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
    return respond(HttpStatus.NOT_FOUND, ex.getMessage(), request, ex);
  }

  @ExceptionHandler(BadRequestException.class)
  public ResponseEntity<BaseResponse<Void>> handleBadRequest(BadRequestException ex, HttpServletRequest request) {
    return respond(HttpStatus.BAD_REQUEST, ex.getMessage(), request, ex);
  }

  @ExceptionHandler(ConflictException.class)
  public ResponseEntity<BaseResponse<Void>> handleConflict(ConflictException ex, HttpServletRequest request) {
    return respond(HttpStatus.CONFLICT, ex.getMessage(), request, ex);
  }

  @ExceptionHandler(RateLimitExceededException.class)
  public ResponseEntity<BaseResponse<Void>> handleRateLimitExceeded(RateLimitExceededException ex,
      HttpServletRequest request) {
    return respond(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), request, ex);
  }

  /** The account exists and the caller proved who they are, but it may not be used. */
  @ExceptionHandler(UserNotActiveException.class)
  public ResponseEntity<BaseResponse<Void>> handleUserNotActive(UserNotActiveException ex,
      HttpServletRequest request) {
    return respond(HttpStatus.FORBIDDEN, ACCOUNT_NOT_ACTIVE, request, ex);
  }

  // ------------------------------------------------------------------ security

  /** Bearer/refresh token rejected; its message is one of a few fixed constants, never user input. */
  @ExceptionHandler(InvalidTokenException.class)
  public ResponseEntity<BaseResponse<Void>> handleInvalidToken(InvalidTokenException ex, HttpServletRequest request) {
    return respond(HttpStatus.UNAUTHORIZED, ex.getMessage(), request, ex);
  }

  /** Same text for "no such user" and "wrong password", so callers cannot enumerate accounts. */
  @ExceptionHandler({ BadCredentialsException.class, UsernameNotFoundException.class })
  public ResponseEntity<BaseResponse<Void>> handleBadCredentials(AuthenticationException ex,
      HttpServletRequest request) {
    return respond(HttpStatus.UNAUTHORIZED, INVALID_CREDENTIALS, request, ex);
  }

  /**
   * Spring Security wraps whatever the user lookup threw. A wrapped
   * {@link UserNotActiveException} is a client problem (403); anything else
   * signals an infrastructure failure (e.g. the database is down), so it is a
   * 500 with the generic text, logged at ERROR with its cause. The message is
   * never echoed. Must stay more specific than the
   * {@link AuthenticationException} mapping below.
   */
  @ExceptionHandler(InternalAuthenticationServiceException.class)
  public ResponseEntity<BaseResponse<Void>> handleInternalAuthenticationService(
      InternalAuthenticationServiceException ex, HttpServletRequest request) {
    if (ex.getCause() instanceof UserNotActiveException) {
      return respond(HttpStatus.FORBIDDEN, ACCOUNT_NOT_ACTIVE, request, ex);
    }
    return respond(HttpStatus.INTERNAL_SERVER_ERROR, GENERIC_SERVER_ERROR, request, ex);
  }

  /** Any other Spring Security authentication failure (disabled, locked, expired, ...). */
  @ExceptionHandler(AuthenticationException.class)
  public ResponseEntity<BaseResponse<Void>> handleAuthentication(AuthenticationException ex,
      HttpServletRequest request) {
    return respond(HttpStatus.UNAUTHORIZED, AUTHENTICATION_FAILED, request, ex);
  }

  /** Expired, malformed or badly signed JWT reaching a controller (e.g. logout). */
  @ExceptionHandler(JwtException.class)
  public ResponseEntity<BaseResponse<Void>> handleJwt(JwtException ex, HttpServletRequest request) {
    return respond(HttpStatus.UNAUTHORIZED, INVALID_TOKEN, request, ex);
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<BaseResponse<Void>> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
    return respond(HttpStatus.FORBIDDEN, ACCESS_DENIED, request, ex);
  }

  // ------------------------------------------------------------------ persistence and bean validation

  /** The message carries the SQL statement and constraint name: never returned, DEBUG-logged only. */
  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<BaseResponse<Void>> handleDataIntegrity(DataIntegrityViolationException ex,
      HttpServletRequest request) {
    return respond(HttpStatus.CONFLICT, DATA_CONFLICT, request, ex);
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<BaseResponse<Void>> handleConstraintViolation(ConstraintViolationException ex,
      HttpServletRequest request) {
    return respond(HttpStatus.BAD_REQUEST, violationMessages(ex), request, ex);
  }

  @ExceptionHandler(TransactionSystemException.class)
  public ResponseEntity<BaseResponse<Void>> handleTransaction(TransactionSystemException ex,
      HttpServletRequest request) {
    if (ex.getRootCause() instanceof ConstraintViolationException violations) {
      return respond(HttpStatus.BAD_REQUEST, violationMessages(violations), request, ex);
    }
    return respond(HttpStatus.INTERNAL_SERVER_ERROR, GENERIC_SERVER_ERROR, request, ex);
  }

  // ------------------------------------------------------------------ fallback

  @ExceptionHandler(Exception.class)
  public ResponseEntity<BaseResponse<Void>> handleUnexpected(Exception ex, HttpServletRequest request) {
    return respond(HttpStatus.INTERNAL_SERVER_ERROR, GENERIC_SERVER_ERROR, request, ex);
  }

  // ------------------------------------------------------------------ Spring MVC (ResponseEntityExceptionHandler)

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
      HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    String message = ex.getBindingResult().getAllErrors().stream()
        .map(error -> error instanceof FieldError fieldError
            ? fieldError.getField() + ": " + fieldError.getDefaultMessage()
            : error.getDefaultMessage())
        .collect(Collectors.joining(", "));
    return handleExceptionInternal(ex, BaseResponse.error(status, orDefault(message)), headers, status, request);
  }

  @Override
  protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
      HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    String message = ex.getParameterValidationResults().stream()
        .flatMap(result -> result.getResolvableErrors().stream()
            .map(error -> result.getMethodParameter().getParameterName() + ": " + error.getDefaultMessage()))
        .collect(Collectors.joining(", "));
    return handleExceptionInternal(ex, BaseResponse.error(status, orDefault(message)), headers, status, request);
  }

  /**
   * Single exit for every exception the base class handles: replaces the
   * ProblemDetail body with a {@link BaseResponse}, keeps the status and
   * headers (e.g. {@code Allow} on 405) the base class chose.
   */
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body, HttpHeaders headers,
      HttpStatusCode statusCode, WebRequest request) {
    Object responseBody = body instanceof BaseResponse<?>
        ? body
        : BaseResponse.error(statusCode, clientMessage(ex, body, statusCode));
    logByStatus(statusCode, path(request), ex);
    return super.handleExceptionInternal(ex, responseBody, headers, statusCode, request);
  }

  // ------------------------------------------------------------------ helpers

  private ResponseEntity<BaseResponse<Void>> respond(HttpStatus status, String message, HttpServletRequest request,
      Exception ex) {
    logByStatus(status, request.getRequestURI(), ex);
    return ResponseBuilder.error(status, message);
  }

  private static void logByStatus(HttpStatusCode status, String path, Exception ex) {
    int code = status.value();
    if (status.is5xxServerError()) {
      log.error("{} at {}", code, path, ex);
      return;
    }
    if (code == 401 || code == 403 || code == 429) {
      log.warn("{} {} at {}", code, ex.getClass().getSimpleName(), path);
    } else {
      log.info("{} {} at {}", code, ex.getClass().getSimpleName(), path);
    }
    log.debug("{} detail: {}", code, ex.getMessage());
  }

  /** Message shown to the client for a framework exception. Never the raw exception message. */
  private static String clientMessage(Exception ex, @Nullable Object body, HttpStatusCode status) {
    if (status.is5xxServerError()) {
      return GENERIC_SERVER_ERROR;
    }
    String detail = null;
    if (body instanceof ProblemDetail problemDetail) {
      detail = problemDetail.getDetail();
    } else if (ex instanceof ErrorResponse errorResponse) {
      detail = errorResponse.getBody().getDetail();
    }
    if (StringUtils.hasText(detail)) {
      return detail;
    }
    HttpStatus known = HttpStatus.resolve(status.value());
    return known != null ? known.getReasonPhrase() : "Request failed";
  }

  private static String violationMessages(ConstraintViolationException ex) {
    Set<ConstraintViolation<?>> violations = ex.getConstraintViolations();
    if (violations == null || violations.isEmpty()) {
      return VALIDATION_FAILED;
    }
    return violations.stream()
        .map(ConstraintViolation::getMessage)
        .sorted()
        .collect(Collectors.joining(", "));
  }

  private static String orDefault(String message) {
    return StringUtils.hasText(message) ? message : VALIDATION_FAILED;
  }

  private static String path(WebRequest request) {
    if (request instanceof ServletWebRequest servletWebRequest) {
      return servletWebRequest.getRequest().getRequestURI();
    }
    return request.getDescription(false);
  }
}
