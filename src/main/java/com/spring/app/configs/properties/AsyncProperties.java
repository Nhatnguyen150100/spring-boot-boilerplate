package com.spring.app.configs.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
@Component
@ConfigurationProperties(prefix = "spring.task.async.execution.pool")
public class AsyncProperties {

  @Min(value = 1, message = "Core pool size must be at least 1")
  private int coreSize = 5;

  @Min(value = 1, message = "Max pool size must be at least 1")
  private int maxSize = 10;

  @Min(value = 1, message = "Queue capacity must be at least 1")
  private int queueCapacity = 25;
}
