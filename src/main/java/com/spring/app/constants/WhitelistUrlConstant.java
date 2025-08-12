package com.spring.app.constants;

public class WhitelistUrlConstant {

  public static final String OAUTH2_LOGIN_URL = "/auth/oauth2/authorization";
  public static final String OAUTH2_REDIRECT_URL = "/auth/oauth2/code/google";

  public static final String[] PUBLIC_URLS = {
      "/auth/**"
  };

  public static final String[] PUBLIC_GET_URLS = {
      "/v3/api-docs/**",
      "/swagger-ui/**",
      "/swagger-ui.html",
      "/swagger-resources/**",
      "/swagger-ui/**",
      "/swagger-ui.html",
      "/actuator/**",
      "/oauth2/**"
  };

}
