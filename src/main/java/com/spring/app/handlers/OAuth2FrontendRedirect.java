package com.spring.app.handlers;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.spring.app.configs.properties.ApplicationProperties;
import com.spring.app.configs.properties.AuthProperties;
import com.spring.app.modules.auth.dto.response.TokenResponseDto;

import lombok.RequiredArgsConstructor;

/**
 * Builds the URL the browser is sent to after an OAuth2 login:
 * {@code application.frontend-url + application.auth.oauth2-redirect-path}.
 *
 * <p>
 * Results travel in the URL <em>fragment</em>: browsers never send it to a
 * server, so the tokens do not reach access logs or Referer headers. Error
 * codes are restricted to {@code [a-z0-9_]}, never a free-text message.
 * </p>
 */
@Component
@RequiredArgsConstructor
public class OAuth2FrontendRedirect {

  private static final Pattern SAFE_ERROR_CODE = Pattern.compile("[a-z0-9_]{1,64}");
  private static final String GENERIC_ERROR = "authentication_failed";

  private final ApplicationProperties applicationProperties;
  private final AuthProperties authProperties;

  public String success(TokenResponseDto tokens) {
    return base()
        + "#access_token=" + encode(tokens.accessToken())
        + "&refresh_token=" + encode(tokens.refreshToken())
        + "&token_type=Bearer";
  }

  public String failure(String errorCode) {
    return base() + "#error=" + safeErrorCode(errorCode);
  }

  /**
   * The code itself when it matches {@code [a-z0-9_]{1,64}}, otherwise
   * {@code authentication_failed}. Provider error codes can arrive in the
   * callback's query string, so anything else is never echoed or logged.
   */
  static String safeErrorCode(String errorCode) {
    return errorCode != null && SAFE_ERROR_CODE.matcher(errorCode).matches() ? errorCode : GENERIC_ERROR;
  }

  private String base() {
    String frontendUrl = applicationProperties.getFrontendUrl();
    String trimmed = frontendUrl.endsWith("/") ? frontendUrl.substring(0, frontendUrl.length() - 1) : frontendUrl;
    return trimmed + authProperties.getOauth2RedirectPath();
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
