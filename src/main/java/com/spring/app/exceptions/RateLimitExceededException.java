package com.spring.app.exceptions;

public class RateLimitExceededException extends RuntimeException {

  /**
   * {@code Retry-After} sent with every 429: the shortest rate-limit window. A
   * client over a longer quota (hourly, daily, login lockout) retries in vain,
   * but harmlessly.
   */
  public static final String RETRY_AFTER_SECONDS = "60";

  public RateLimitExceededException(String message) {
    super(message);
  }

  public RateLimitExceededException(String message, Throwable cause) {
    super(message, cause);
  }
}
