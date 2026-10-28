package com.spring.app.configs.properties;

import java.util.Base64;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.AssertTrue;
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

  /** HS256 needs a key of at least 256 bits. */
  public static final int MIN_SECRET_KEY_BYTES = 32;

  /** Base64-encoded secret. Must decode to at least 256 bits for HS256. */
  @NotBlank(message = "application.security.jwt.secret-key is required")
  private String secretKey;

  /** Access-token lifetime in milliseconds. */
  @Min(value = 60000, message = "application.security.jwt.expiration must be >= 60000ms")
  private long expiration = 86400000;

  /** Refresh-token lifetime in milliseconds. */
  @Min(value = 60000, message = "application.security.jwt.refresh-expiration must be >= 60000ms")
  private long refreshExpiration = 604800000;

  /**
   * Fails startup when the secret is not Base64 or is shorter than 256 bits,
   * instead of failing on the first login. A blank value is reported by
   * {@code @NotBlank} alone.
   */
  @AssertTrue(message = "application.security.jwt.secret-key must be Base64 and decode to at least 256 bits (32 bytes)")
  public boolean isSecretKeyStrong() {
    if (secretKey == null || secretKey.isBlank()) {
      return true;
    }
    try {
      return Base64.getDecoder().decode(secretKey.trim()).length >= MIN_SECRET_KEY_BYTES;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
