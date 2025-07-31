package com.spring.app.exceptions;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import com.spring.app.common.response.BaseResponse;

import jakarta.mail.MessagingException;

import java.util.ArrayList;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

  /**
   * Handles exceptions of type
   * {@link ResourceNotFoundException}.
   *
   * @param ex The exception to handle.
   * @return A {@link ResourceNotFoundException} with a status of
   *         {@link HttpStatus#NOT_FOUND} and the message of the exception.
   */
  @ExceptionHandler(ResourceNotFoundException.class)
  public ResponseEntity<BaseResponse<Void>> handleNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
    log.error("Not found at end point: {} - Message: {}", request.getRequestURI(), ex.getMessage());
    return buildErrorResponse(HttpStatus.NOT_FOUND, ex.getMessage());
  }

  /**
   * Handles exceptions of type
   * {@link BadRequestException}.
   *
   * @param ex The exception to handle.
   * @return A {@link BadRequestException} with a status of
   *         {@link HttpStatus#BAD_REQUEST} and the message of the exception.
   */
  @ExceptionHandler(BadRequestException.class)
  public ResponseEntity<BaseResponse<Void>> handleBadRequest(BadRequestException ex, HttpServletRequest request) {
    log.error("Bad request at end point: {} - Message: {}", request.getRequestURI(), ex.getMessage());
    return buildErrorResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
  }

  @ExceptionHandler(ConflictException.class)
  public ResponseEntity<BaseResponse<Void>> handleConflictException(ConflictException ex, HttpServletRequest request) {
    log.error("Conflict at end point: {} - Message: {}", request.getRequestURI(), ex.getMessage());
    return buildErrorResponse(HttpStatus.CONFLICT, ex.getMessage());
  }

  /**
   * Handles exceptions of type
   * {@link MethodArgumentNotValidException}. These are thrown when a request
   * parameter is invalid (i.e. fails validation). The response will have a
   * status of {@link HttpStatus#BAD_REQUEST} and a message that is a
   * comma-separated
   * list of the fields that failed validation and their associated error
   * messages.
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<BaseResponse<Void>> handleValidation(MethodArgumentNotValidException ex,
      HttpServletRequest request) {
    String message = ex.getBindingResult()
        .getFieldErrors()
        .stream()
        .map(err -> err.getField() + ": " + err.getDefaultMessage())
        .collect(Collectors.joining(", "));
    log.error("Validation error at end point: {} - Message: {}", request.getRequestURI(), message);
    return buildErrorResponse(HttpStatus.BAD_REQUEST, message);
  }

  /**
   * Handles exceptions of type
   * {@link BadCredentialsException}. These are thrown when there is a problem
   * with the credentials provided (i.e. the username or password are invalid).
   * The response will have a status of {@link HttpStatus#UNAUTHORIZED} and a
   * message that indicates that the username or password were invalid.
   */
  @ExceptionHandler(BadCredentialsException.class)
  public ResponseEntity<BaseResponse<Void>> handleBadCredentials(BadCredentialsException ex,
      HttpServletRequest request) {
    if (ex.getCause() instanceof UsernameNotFoundException) {
      log.error("Bad credentials at end point: {} - Message: {}", request.getRequestURI(), ex.getMessage());
      return buildErrorResponse(HttpStatus.BAD_REQUEST, "Invalid username or password");
    }
    log.error("Bad credentials at end point: {} - Message: {}", request.getRequestURI(), ex.getMessage());
    return buildErrorResponse(HttpStatus.UNAUTHORIZED, ex.getMessage());
  }

  /**
   * Handles exceptions of type {@link InternalAuthenticationServiceException}.
   * These are thrown when there is a problem with the internal authentication
   * service. If the cause of the exception is a {@link UserNotActiveException},
   * the response will have a status of {@link HttpStatus#FORBIDDEN} and a message
   * indicating that the user account is not active. Otherwise, the response will
   * have a status of {@link HttpStatus#UNAUTHORIZED} and a message indicating
   * that the authentication failed.
   *
   * @param ex The exception to handle.
   * @return A {@link ResponseEntity} with an appropriate status and error
   *         message.
   */
  @ExceptionHandler(InternalAuthenticationServiceException.class)
  public ResponseEntity<BaseResponse<Void>> handleInternalAuthenticationServiceException(
      InternalAuthenticationServiceException ex) {
    if (ex.getCause() instanceof UserNotActiveException) {
      log.error("User not active: {}", ex.getMessage());
      return buildErrorResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }
    return buildErrorResponse(HttpStatus.UNAUTHORIZED, "Authentication failed: " + ex.getMessage());
  }

  /**
   * Handles exceptions of type {@link AccessDeniedException}.
   * These are thrown when a user tries to access a resource they are
   * not authorized to access. The response will have a status of
   * {@link HttpStatus#FORBIDDEN} and a message indicating that
   * permission is denied.
   *
   * @param ex The exception to handle.
   * @return A {@link ResponseEntity} with a status of
   *         {@link HttpStatus#FORBIDDEN} and an error message.
   */
  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<BaseResponse<Void>> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
    log.error("Access denied at end point: {} - Message: {}", request.getRequestURI(), ex.getMessage());
    return buildErrorResponse(HttpStatus.FORBIDDEN, "You do not have permission to access this resource");
  }

  /**
   * Handles exceptions of type {@link TransactionSystemException}. These
   * are thrown when something goes wrong with a transaction. If the
   * underlying cause is a {@link ConstraintViolationException}, the
   * response will have a status of {@link HttpStatus#BAD_REQUEST} and a
   * message containing all the validation errors. Otherwise, the response
   * will have a status of {@link HttpStatus#INTERNAL_SERVER_ERROR} and a
   * message indicating that there was an internal server error.
   *
   * @param ex The exception to handle.
   * @return A {@link ResponseEntity} with an appropriate status and
   *         error message.
   */
  @ExceptionHandler(TransactionSystemException.class)
  public ResponseEntity<?> handleTransactionException(TransactionSystemException ex, HttpServletRequest request) {
    Throwable cause = ex.getRootCause();
    if (cause instanceof ConstraintViolationException violationEx) {
      ArrayList<String> errors = new ArrayList<>();
      violationEx.getConstraintViolations()
          .forEach(violation -> errors.add(violation.getMessage()));
      return buildErrorResponse(HttpStatus.BAD_REQUEST, errors.stream().collect(Collectors.joining(",")));
    }

    log.error("Transaction error at end point: {} - Message: {}", request.getRequestURI(), ex.getMessage());

    return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Server error" + ex.getMessage());
  }

  /**
   * Handles exceptions of type {@link Exception} that are not handled
   * elsewhere in the application. These are typically unexpected
   * exceptions that are not directly related to user input or the
   * specific actions of the application. The response will have a
   * status of {@link HttpStatus#INTERNAL_SERVER_ERROR} and a message
   * that includes the message of the exception.
   * 
   * @param ex      The exception to handle.
   * @param request The current HTTP request.
   * @return A {@link ResponseEntity} with a status of
   *         {@link HttpStatus#INTERNAL_SERVER_ERROR} and an error message.
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<BaseResponse<Void>> handleGeneric(Exception ex, HttpServletRequest request) {
    log.error("Unhandled exception at end point {} - Message: {}", request.getRequestURI(), ex.getMessage());
    return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Server error: " + ex.getMessage());
  }

  /**
   * Handles exceptions of type {@link MessagingException}. These are thrown
   * when there is a problem sending an email. The response will have a status
   * of {@link HttpStatus#INTERNAL_SERVER_ERROR} and a message that includes
   * the message of the exception.
   * 
   * @param ex The exception to handle.
   * @return A {@link ResponseEntity} with a status of
   *         {@link HttpStatus#INTERNAL_SERVER_ERROR} and an error message.
   */
  @ExceptionHandler(MessagingException.class)
  public ResponseEntity<BaseResponse<Void>> handleMessagingException(MessagingException ex,
      HttpServletRequest request) {
    log.error("Messaging exception at end point: {} - Message: {}", request.getRequestURI(), ex.getMessage());
    return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Send email error: " + ex.getMessage());
  }

  /**
   * Builds an error response with the specified HTTP status and message.
   *
   * @param status  The HTTP status to set for the response.
   * @param message The error message to include in the response body.
   * @return A {@link ResponseEntity} containing a {@link BaseResponse} with the
   *         specified status and error message.
   */
  private ResponseEntity<BaseResponse<Void>> buildErrorResponse(HttpStatus status, String message) {
    return ResponseEntity
        .status(status)
        .body(BaseResponse.error(status, message));
  }
}