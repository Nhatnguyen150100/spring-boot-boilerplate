package com.spring.app.constants;

/**
 * Single source of truth for which URLs skip authentication and which need
 * ADMIN. Only {@code SecurityConfig} reads the PUBLIC_* / ADMIN_* arrays;
 * {@code JwtAuthenticatorFilter} keeps no copy (it authenticates whenever a
 * bearer token is present and leaves the decision to these rules).
 */
public final class WhitelistUrlConstant {

  private WhitelistUrlConstant() {
  }

  public static final String OAUTH2_LOGIN_URL    = ApplicationConstants.AUTH_BASE_PATH + "/oauth2/authorization";
  public static final String OAUTH2_REDIRECT_URL = ApplicationConstants.AUTH_BASE_PATH + "/oauth2/code/google";

  // Rate-limit path groups, read by the rate-limit code (not by SecurityConfig).
  public static final String[] AUTH_ENDPOINTS_RATELIMIT   = { ApplicationConstants.AUTH_BASE_PATH + "/**" };
  public static final String[] UPLOAD_ENDPOINTS_RATELIMIT = { ApplicationConstants.FILES_BASE_PATH + "/**" };
  public static final String[] API_ENDPOINTS_RATELIMIT    = {};

  /**
   * Anonymous auth endpoints, POST only. logout is deliberately absent: it
   * needs a valid access token.
   */
  public static final String[] PUBLIC_POST_URLS = {
      ApplicationConstants.AUTH_BASE_PATH + "/register",
      ApplicationConstants.AUTH_BASE_PATH + "/resend-otp",
      ApplicationConstants.AUTH_BASE_PATH + "/activate",
      ApplicationConstants.AUTH_BASE_PATH + "/login",
      ApplicationConstants.AUTH_BASE_PATH + "/refresh-token",
      ApplicationConstants.AUTH_BASE_PATH + "/forgot-password",
      ApplicationConstants.AUTH_BASE_PATH + "/reset-password"
  };

  /** OAuth2 handshake endpoints (any method). */
  public static final String[] PUBLIC_URLS = {
      OAUTH2_LOGIN_URL + "/**",
      OAUTH2_REDIRECT_URL
  };

  public static final String[] PUBLIC_GET_URLS = {
      "/api-docs/**",        // springdoc.api-docs.path (springdoc config is owned by P4)
      "/v3/api-docs/**",
      "/swagger-ui/**",
      "/swagger-ui.html",
      "/actuator/health",
      "/actuator/health/**",
      "/actuator/info"
  };

  /** Every other actuator endpoint (prometheus, ...) is ADMIN-only. */
  public static final String[] ADMIN_URLS = {
      "/actuator/**"
  };
}
