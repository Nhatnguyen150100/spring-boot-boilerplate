package com.spring.app.configs.properties;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * Authentication behaviour, bound from {@code application.auth.*}.
 *
 * <p>
 * Strict binding: a typo'd key under {@code application.auth} fails startup.
 * </p>
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "application.auth", ignoreUnknownFields = false)
public class AuthProperties {

  /**
   * Register new accounts as ACTIVE without the e-mail OTP step. A local-dev
   * convenience only: honoured solely while the {@code dev} profile is active
   * as well, so a deployment that inherits the flag by mistake still verifies
   * e-mail.
   */
  private boolean skipEmailVerification = false;

  /**
   * Failed logins allowed per (e-mail, client IP) inside
   * {@link #loginFailureWindow} before further attempts are refused (429).
   * Successful logins are never counted.
   */
  @Min(value = 1, message = "application.auth.login-max-failures must be >= 1")
  private int loginMaxFailures = 5;

  /** How long failed logins are remembered; the lock lifts when it expires. */
  @NotNull(message = "application.auth.login-failure-window is required")
  @DurationMin(seconds = 1, message = "application.auth.login-failure-window must be at least 1s")
  private Duration loginFailureWindow = Duration.ofMinutes(15);

  /**
   * Wrong guesses allowed per OTP and purpose (activation, password reset);
   * the last one discards the OTP, so the user has to request a new one.
   */
  @Min(value = 1, message = "application.auth.otp-max-attempts must be >= 1")
  private int otpMaxAttempts = 5;

  /**
   * SPA route, appended to {@code application.frontend-url}, that receives the
   * OAuth2 result in the URL fragment ({@code #access_token=...} or
   * {@code #error=...}).
   */
  @NotBlank(message = "application.auth.oauth2-redirect-path is required")
  @Pattern(regexp = "^/.*", message = "application.auth.oauth2-redirect-path must start with '/'")
  private String oauth2RedirectPath = "/oauth2/callback";
}
