package com.spring.app.constants;

public class WhitelistUrlConstant {

  public static final String OAUTH2_LOGIN_URL = "/api/v1/auth/oauth2/authorization";
  public static final String OAUTH2_REDIRECT_URL = "/api/v1/auth/oauth2/code/google";

  public static final String[] AUTH_ENDPOINTS_RATELIMIT = {
      "/api/v1/auth/**"
  };

  public static final String[] UPLOAD_ENDPOINTS_RATELIMIT = {
      "/api/v1/files/**"
  };

  public static final String[] API_ENDPOINTS_RATELIMIT = {};

  public static final String[] PUBLIC_URLS = {
      "/api/v1/auth/**"
  };

  public static final String[] PUBLIC_GET_URLS = {
      "/v3/api-docs/**",
      "/swagger-ui/**",
      "/swagger-ui.html",
      "/swagger-resources/**",
      "/actuator/health/**",
      "/actuator/info",
      "/oauth2/**"
  };
}
