package com.spring.app.configs.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "spring.mail")
public class MailProperties {

  @NotBlank(message = "Mail app name is required")
  private String app;

  @NotBlank(message = "Mail host is required")
  private String host;

  @Min(value = 1, message = "Port must be greater than or equal to 1")
  private int port;

  @Email(message = "From email must be a valid email address")
  private String from;

  @Email(message = "Username must be a valid email address")
  private String username;

  @NotBlank(message = "Mail password is required")
  private String password;

  private boolean smtpAuth = true;

  private boolean startTls = true;

  private String defaultEncoding = "UTF-8";
}
