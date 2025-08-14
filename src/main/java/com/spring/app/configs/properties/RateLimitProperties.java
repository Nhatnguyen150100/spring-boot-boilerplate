package com.spring.app.configs.properties;

import lombok.Data;
import lombok.EqualsAndHashCode;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {

  private Auth auth = new Auth();
  private Global global = new Global();
  private Upload upload = new Upload();
  private Api api = new Api();

  @Data
  public static class BaseRateLimitConfig {
    private int requestsPerMinute;
    private int requestsPerHour;
    private int requestsPerDay;
    private int burstCapacity;
    private boolean enabled;
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
