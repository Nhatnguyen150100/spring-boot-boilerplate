package com.spring.app.constants;

public class WhitelistUrlConstant {
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
  };

}
