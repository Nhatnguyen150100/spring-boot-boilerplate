package com.spring.app.configs.properties;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Redis cache TTLs.
 *
 * <p>
 * Durations use the shorthand Spring Boot understands ({@code 30m},
 * {@code 5s}, {@code 1h}), which reviews better than a raw millisecond count.
 * </p>
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "application.cache", ignoreUnknownFields = false)
public class CacheProperties {

  /** TTL applied to every cache without a specific entry below. */
  @NotNull
  private Duration defaultTtl = Duration.ofMinutes(30);

  /** TTL for the {@code users} and {@code userProfile} caches. */
  @NotNull
  private Duration usersTtl = Duration.ofMinutes(10);

  /** TTL for the {@code tokens} cache. */
  @NotNull
  private Duration tokensTtl = Duration.ofMinutes(5);

  /**
   * Whether {@code null} results are cached. Off by default, so a miss is not
   * remembered as "no such row".
   */
  private boolean cacheNullValues = false;
}
