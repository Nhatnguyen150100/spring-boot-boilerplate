package com.spring.app.exceptions;

/**
 * The credentials were correct but the account is not ACTIVE (PENDING,
 * INACTIVE). Thrown only after the password matched, so it reveals nothing to
 * someone who does not know the password. Extends {@link BadRequestException}
 * so it is a client error even before GlobalExceptionHandler maps it
 * explicitly (403).
 */
public class UserNotActiveException extends BadRequestException {

  public UserNotActiveException(String message) {
    super(message);
  }

  public UserNotActiveException(String message, Throwable cause) {
    super(message);
    initCause(cause);
  }
}
