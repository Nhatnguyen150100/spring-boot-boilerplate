package com.spring.app.configs.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
@Component
@ConfigurationProperties(prefix = "spring.cache.redis")
public class CacheProperties {
  @Min(60)
  private int timeToLive = 1800000;
  private boolean cacheNullValues;
}
