package com.spring.app.configs.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

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
}
