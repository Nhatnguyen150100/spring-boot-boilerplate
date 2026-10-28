package com.spring.app.configs.properties;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@link AsyncProperties} the way the application does (strict binding +
 * {@code @Validated}), without application.yml and without a full context.
 */
class AsyncPropertiesTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(AsyncProperties.class)
  static class Config {
  }

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withUserConfiguration(Config.class);

  @Test
  void bindsEmailPool() {
    runner.withPropertyValues(
        "application.async.email.core-size=3",
        "application.async.email.max-size=6",
        "application.async.email.queue-capacity=40")
        .run(context -> {
          assertThat(context).hasNotFailed();
          AsyncProperties.Pool email = context.getBean(AsyncProperties.class).getEmail();
          assertThat(email.getCoreSize()).isEqualTo(3);
          assertThat(email.getMaxSize()).isEqualTo(6);
          assertThat(email.getQueueCapacity()).isEqualTo(40);
        });
  }

  @Test
  void defaultsToBoundedEmailPool() {
    runner.run(context -> {
      assertThat(context).hasNotFailed();
      AsyncProperties.Pool email = context.getBean(AsyncProperties.class).getEmail();
      assertThat(email.getCoreSize()).isEqualTo(5);
      assertThat(email.getMaxSize()).isEqualTo(5);
      assertThat(email.getQueueCapacity()).isEqualTo(100);
    });
  }

  @Test
  void rejectsZeroCoreSize() {
    runner.withPropertyValues("application.async.email.core-size=0")
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure()).rootCause()
              .hasMessageContaining("core-size must be at least 1");
        });
  }

  @Test
  void rejectsMaxSizeBelowCoreSize() {
    runner.withPropertyValues(
        "application.async.email.core-size=4",
        "application.async.email.max-size=2")
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure()).rootCause()
              .hasMessageContaining("max-size must be greater than or equal to core-size");
        });
  }
}
