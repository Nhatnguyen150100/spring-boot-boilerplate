package com.spring.app.constants;

public final class ApplicationConstants {

  // Cache names
  public static final String CACHE_USERS = "users";
  public static final String CACHE_TOKENS = "tokens";
  public static final String CACHE_PERMISSIONS = "permissions";

  // Time constants
  public static final int OTP_EXPIRATION_MINUTES = 3;
  public static final int JWT_EXPIRATION_HOURS = 24;
  public static final int REFRESH_TOKEN_EXPIRATION_DAYS = 7;

  // Validation constants
  public static final int MIN_PASSWORD_LENGTH = 8;
  public static final int MAX_EMAIL_LENGTH = 255;
  public static final int MAX_NAME_LENGTH = 100;

  // API endpoints
  public static final String API_BASE_PATH = "/api/v1";
  public static final String AUTH_BASE_PATH = API_BASE_PATH + "/auth";
  public static final String USER_BASE_PATH = API_BASE_PATH + "/users";

  // Error messages
  public static final String EMAIL_ALREADY_EXISTS = "Email already exists";
  public static final String USER_NOT_FOUND = "User not found";
  public static final String INVALID_CREDENTIALS = "Invalid credentials";
  public static final String ACCOUNT_NOT_ACTIVE = "Account is not active";
}