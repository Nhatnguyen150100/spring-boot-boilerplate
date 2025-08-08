package com.spring.app.configs.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Component
@ConfigurationProperties(prefix = "application.security.jwt")
public class JwtProperties {

  @NotBlank(message = "JWT secret key is required")
  private String secretKey;

  @Min(value = 60000, message = "expiration must be >= 60000ms")
  private long expiration = 86400000;

  @Min(value = 60000, message = "refreshExpiration must be >= 60000ms")
  private long refreshExpiration = 604800000;

}