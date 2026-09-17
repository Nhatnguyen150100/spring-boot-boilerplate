package com.spring.app.configs.properties;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

/**
 * Cross-cutting application settings bound from the {@code application.*}
 * namespace.
 *
 * <p>
 * Convention used across this project: {@code spring.*} is reserved for
 * framework properties, every setting owned by this application lives under
 * {@code application.*}.
 * </p>
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "application")
public class ApplicationProperties {

  @Valid
  private Cors cors = new Cors();

  @Valid
  private Redis redis = new Redis();

  /** Public base URL of the frontend, used to build links in outgoing e-mails. */
  private String frontendUrl = "http://localhost:3000";

  /**
   * Reverse-proxy IPs allowed to set {@code X-Forwarded-For} / {@code X-Real-IP}.
   * Only when a request originates from one of these is the forwarded IP honoured
   * for rate limiting. Empty (default) means those headers are never trusted,
   * which is the safe choice when the app is exposed directly.
   */
  private List<String> trustedProxies = new ArrayList<>();

  /**
   * Optional whitelist of e-mail domains allowed to sign in via OAuth2 (e.g.
   * {@code solashi.com}). Empty means any domain is accepted.
   */
  private List<String> oauth2AllowedEmailDomains = new ArrayList<>();

  @Data
  public static class Cors {

    /** Origins allowed to call the API. Must not be empty. */
    @NotEmpty(message = "application.cors.allowed-origins must contain at least one origin")
    private List<String> allowedOrigins = new ArrayList<>();

    /** How long a browser may cache the preflight response, in seconds. */
    private long maxAgeSeconds = 3600;
  }

  @Data
  public static class Redis {

    /**
     * Stop startup when Redis is unreachable. Redis is not optional at runtime
     * -- rate limiting, caching and refresh-token storage all depend on it --
     * so by default a missing Redis kills the process instead of surfacing
     * later as a flood of request-time errors. Turned off for tests.
     */
    private boolean failFast = true;
  }
}
