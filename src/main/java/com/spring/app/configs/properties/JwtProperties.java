package com.spring.app.configs.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * JWT signing and lifetime settings.
 *
 * <p>
 * {@code secretKey} has no default on purpose: the {@code prod} profile leaves
 * the placeholder without a fallback, so a deployment that forgets to set
 * {@code JWT_SECRET_KEY} fails at startup rather than signing tokens with a
 * value that leaked into source control.
 * </p>
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "application.security.jwt", ignoreUnknownFields = false)
public class JwtProperties {

  /** Base64-encoded secret. Must be at least 256-bit for HS256. */
  @NotBlank(message = "application.security.jwt.secret-key is required")
  private String secretKey;

  /** Access-token lifetime in milliseconds. */
  @Min(value = 60000, message = "application.security.jwt.expiration must be >= 60000ms")
  private long expiration = 86400000;

  /** Refresh-token lifetime in milliseconds. */
  @Min(value = 60000, message = "application.security.jwt.refresh-expiration must be >= 60000ms")
  private long refreshExpiration = 604800000;
}