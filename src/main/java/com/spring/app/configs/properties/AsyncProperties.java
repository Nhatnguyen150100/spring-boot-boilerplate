package com.spring.app.configs.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Thread pools backing {@code @Async} execution. */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "application.async", ignoreUnknownFields = false)
public class AsyncProperties {

  /** General-purpose pool, exposed as the {@code taskExecutor} bean. */
  @Valid
  private Pool task = new Pool(5, 10, 25);

  /**
   * Bounded pool of platform threads for outgoing e-mail, exposed as the
   * {@code emailExecutor} bean. Its {@code max-size} is also the cap on
   * concurrent SMTP sessions. Deliberately not virtual threads: jakarta.mail's
   * SMTPTransport does socket I/O inside synchronized methods, which pins the
   * carrier thread on Java 21.
   */
  @Valid
  private Pool email = new Pool(5, 5, 100);

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Pool {

    @Min(value = 1, message = "core-size must be at least 1")
    private int coreSize = 1;

    /**
     * Only used once the queue is full: a ThreadPoolExecutor grows past
     * core-size only when it cannot queue.
     */
    @Min(value = 1, message = "max-size must be at least 1")
    private int maxSize = 1;

    @Min(value = 1, message = "queue-capacity must be at least 1")
    private int queueCapacity = 1;

    /** ThreadPoolExecutor rejects max < core at startup with a less helpful error. */
    @AssertTrue(message = "max-size must be greater than or equal to core-size")
    public boolean isMaxSizeAtLeastCoreSize() {
      return maxSize >= coreSize;
    }
  }
}
