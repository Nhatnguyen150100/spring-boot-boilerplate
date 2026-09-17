package com.spring.app.configs.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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
@ConfigurationProperties(prefix = "application.mail")
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
}
