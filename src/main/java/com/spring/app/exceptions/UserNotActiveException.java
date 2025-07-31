package com.spring.app.exceptions;

public class UserNotActiveException extends RuntimeException {
  public UserNotActiveException(String message) {
    super(message);
  }

  public UserNotActiveException(String message, Throwable cause) {
    super(message, cause);
  }
  
}
