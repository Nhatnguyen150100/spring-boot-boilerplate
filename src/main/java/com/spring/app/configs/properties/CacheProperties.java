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
 * Only caches declared in {@code CacheConfig} exist, so there is no default
 * TTL; and {@code null} is never cached.
 * </p>
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "application.cache", ignoreUnknownFields = false)
public class CacheProperties {

  /** TTL for the {@code userProfile} cache ({@code UserResponseDto} by id). */
  @NotNull
  private Duration usersTtl = Duration.ofMinutes(10);
}
