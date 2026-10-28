package com.spring.app.configs.properties;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * SMTP settings consumed by {@link com.spring.app.configs.MailConfig}.
 *
 * <p>
 * These live under {@code application.mail} rather than {@code spring.mail}
 * because the application builds its own {@code JavaMailSender} and adds keys
 * ({@code app}, {@code from}) that are not part of Spring's mail namespace.
 * </p>
 *
 * <p>
 * {@code username} / {@code password} are deliberately not required, so the
 * application still starts on a developer machine with no SMTP credentials --
 * sending mail is what fails there, not booting.
 * </p>
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "application.mail", ignoreUnknownFields = false)
public class MailProperties {

  /** Display name used in the subject line of outgoing e-mails. */
  @NotBlank(message = "application.mail.app is required")
  private String app = "SPRING_APP";

  @NotBlank(message = "application.mail.host is required")
  private String host = "smtp.gmail.com";

  @Min(value = 1, message = "application.mail.port must be >= 1")
  private int port = 587;

  @Email(message = "application.mail.from must be a valid e-mail address")
  private String from;

  @Email(message = "application.mail.username must be a valid e-mail address")
  private String username;

  private String password;

  private boolean smtpAuth = true;

  private boolean startTls = true;

  /** Implicit SMTPS. Leave off when {@code startTls} is used (ports 587/25). */
  private boolean sslEnable = false;

  private String defaultEncoding = "UTF-8";

  /*
   * SMTP socket timeouts. Without them jakarta.mail waits forever on a stuck
   * server, and every thread of the bounded emailExecutor pool eventually hangs.
   * Capped at 5m because jakarta.mail parses them as int milliseconds.
   */

  /** TCP connect timeout ({@code mail.smtp.connectiontimeout}). */
  @NotNull(message = "application.mail.connection-timeout is required")
  @DurationMin(millis = 1, message = "application.mail.connection-timeout must be between 1ms and 5m")
  @DurationMax(minutes = 5, message = "application.mail.connection-timeout must be between 1ms and 5m")
  private Duration connectionTimeout = Duration.ofSeconds(10);

  /** Socket read timeout ({@code mail.smtp.timeout}). */
  @NotNull(message = "application.mail.read-timeout is required")
  @DurationMin(millis = 1, message = "application.mail.read-timeout must be between 1ms and 5m")
  @DurationMax(minutes = 5, message = "application.mail.read-timeout must be between 1ms and 5m")
  private Duration readTimeout = Duration.ofSeconds(10);

  /** Socket write timeout ({@code mail.smtp.writetimeout}). */
  @NotNull(message = "application.mail.write-timeout is required")
  @DurationMin(millis = 1, message = "application.mail.write-timeout must be between 1ms and 5m")
  @DurationMax(minutes = 5, message = "application.mail.write-timeout must be between 1ms and 5m")
  private Duration writeTimeout = Duration.ofSeconds(10);

  /**
   * Upper bound on simultaneous SMTP sends. Providers cap concurrent sessions
   * per account, so extra sends wait for a free slot instead of failing.
   */
  @Min(value = 1, message = "application.mail.max-concurrent-sends must be >= 1")
  private int maxConcurrentSends = 5;
}
