package com.spring.app.configs.properties;

import lombok.Data;
import lombok.EqualsAndHashCode;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;

/** Per-bucket request quotas enforced by {@code RateLimitFilter}. */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "application.rate-limit", ignoreUnknownFields = false)
public class RateLimitProperties {

  @Valid
  private Auth auth = new Auth();
  @Valid
  private Global global = new Global();
  @Valid
  private Upload upload = new Upload();
  @Valid
  private Api api = new Api();

  @Data
  public static class BaseRateLimitConfig {

    @Min(value = 1, message = "requests-per-minute must be at least 1")
    private int requestsPerMinute = 60;

    @Min(value = 1, message = "requests-per-hour must be at least 1")
    private int requestsPerHour = 600;

    @Min(value = 1, message = "requests-per-day must be at least 1")
    private int requestsPerDay = 6000;

    @Min(value = 1, message = "burst-capacity must be at least 1")
    private int burstCapacity = 100;

    private boolean enabled = true;
  }

  @Data
  @EqualsAndHashCode(callSuper = true)
  public static class Auth extends BaseRateLimitConfig {
  }

  @Data
  @EqualsAndHashCode(callSuper = true)
  public static class Global extends BaseRateLimitConfig {
  }

  @Data
  @EqualsAndHashCode(callSuper = true)
  public static class Upload extends BaseRateLimitConfig {
  }

  @Data
  @EqualsAndHashCode(callSuper = true)
  public static class Api extends BaseRateLimitConfig {
  }
}
