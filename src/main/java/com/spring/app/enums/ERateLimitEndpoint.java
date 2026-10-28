package com.spring.app.enums;

import java.util.List;

import org.springframework.util.PathMatcher;

import com.spring.app.constants.ApplicationConstants;

import lombok.Getter;

/**
 * Rate-limit buckets and the request paths each one covers.
 *
 * <p>
 * Declaration order is match precedence: {@code RateLimitFilter} uses the
 * first bucket whose pattern matches the request path. AUTH and UPLOAD must
 * therefore stay before API (they are also under {@code /api/**}), and GLOBAL,
 * which has no pattern, is the fallback for everything else (actuator,
 * Swagger, {@code /oauth2/**}, ...).
 * </p>
 */
@Getter
public enum ERateLimitEndpoint {
  AUTH("auth", ApplicationConstants.AUTH_BASE_PATH + "/**"),
  UPLOAD("upload", ApplicationConstants.FILES_BASE_PATH + "/**"),
  API("api", "/api/**"),
  GLOBAL("global");

  private final String endpointType;
  private final List<String> pathPatterns;

  ERateLimitEndpoint(String endpointType, String... pathPatterns) {
    this.endpointType = endpointType;
    this.pathPatterns = List.of(pathPatterns);
  }

  public boolean matches(String requestPath, PathMatcher matcher) {
    return pathPatterns.stream().anyMatch(pattern -> matcher.match(pattern, requestPath));
  }
}
