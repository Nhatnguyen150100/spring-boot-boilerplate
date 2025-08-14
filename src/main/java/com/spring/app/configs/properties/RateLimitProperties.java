package com.spring.app.configs.properties;

import lombok.Data;
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

  public interface RateLimitConfig {
    int getRequestsPerMinute();

    int getRequestsPerHour();

    int getRequestsPerDay();

    int getBurstCapacity();

    boolean isEnabled();
  }

  @Data
  public static class Auth implements RateLimitConfig {
    private int requestsPerMinute = 10;
    private int requestsPerHour = 100;
    private int requestsPerDay = 1000;
    private int burstCapacity = 20;
    private boolean enabled = true;
  }

  @Data
  public static class Global implements RateLimitConfig {
    private int requestsPerMinute = 100;
    private int requestsPerHour = 1000;
    private int requestsPerDay = 10000;
    private int burstCapacity = 200;
    private boolean enabled = true;
  }

  @Data
  public static class Upload implements RateLimitConfig {
    private int requestsPerMinute = 5;
    private int requestsPerHour = 50;
    private int requestsPerDay = 500;
    private int burstCapacity = 10;
    private boolean enabled = true;
  }

  @Data
  public static class Api implements RateLimitConfig {
    private int requestsPerMinute = 60;
    private int requestsPerHour = 600;
    private int requestsPerDay = 6000;
    private int burstCapacity = 100;
    private boolean enabled = true;
  }

  public boolean isAuthEnabled() {
    return auth.isEnabled();
  }

  public boolean isGlobalEnabled() {
    return global.isEnabled();
  }

  public boolean isUploadEnabled() {
    return upload.isEnabled();
  }

  public boolean isApiEnabled() {
    return api.isEnabled();
  }
}
