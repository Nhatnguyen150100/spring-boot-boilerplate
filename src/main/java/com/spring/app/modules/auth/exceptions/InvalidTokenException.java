package com.spring.app.modules.auth.exceptions;

import org.springframework.security.authentication.BadCredentialsException;

/**
 * A bearer or refresh token that is missing, malformed, expired, revoked or of
 * the wrong type. It is a Spring Security {@code AuthenticationException}, so
 * the exception handler answers 401 (never 500) without a dedicated mapping.
 */
public class InvalidTokenException extends BadCredentialsException {

  public InvalidTokenException(String message) {
    super(message);
  }
}
