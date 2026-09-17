package com.spring.app.configs.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Thread pools backing {@code @Async} execution. */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "application.async")
public class AsyncProperties {

  /** General-purpose pool, exposed as the {@code taskExecutor} bean. */
  @Valid
  private Pool task = new Pool(5, 10, 25);

  /** Dedicated pool for outgoing e-mail, exposed as {@code emailExecutor}. */
  @Valid
  private Pool email = new Pool(2, 5, 10);

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Pool {

    @Min(value = 1, message = "core-size must be at least 1")
    private int coreSize = 1;

    @Min(value = 1, message = "max-size must be at least 1")
    private int maxSize = 1;

    @Min(value = 1, message = "queue-capacity must be at least 1")
    private int queueCapacity = 1;
  }
}
