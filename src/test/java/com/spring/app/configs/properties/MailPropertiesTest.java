package com.spring.app.configs.properties;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** Binds {@link MailProperties} with strict binding + {@code @Validated}. */
class MailPropertiesTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(MailProperties.class)
  static class Config {
  }

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withUserConfiguration(Config.class);

  @Test
  void bindsSmtpTimeouts() {
    runner.withPropertyValues(
        "application.mail.connection-timeout=3s",
        "application.mail.read-timeout=4s",
        "application.mail.write-timeout=500ms")
        .run(context -> {
          assertThat(context).hasNotFailed();
          MailProperties props = context.getBean(MailProperties.class);
          assertThat(props.getConnectionTimeout()).isEqualTo(Duration.ofSeconds(3));
          assertThat(props.getReadTimeout()).isEqualTo(Duration.ofSeconds(4));
          assertThat(props.getWriteTimeout()).isEqualTo(Duration.ofMillis(500));
        });
  }

  @Test
  void rejectsZeroTimeout() {
    runner.withPropertyValues("application.mail.connection-timeout=0s")
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure()).rootCause()
              .hasMessageContaining("application.mail.connection-timeout must be between 1ms and 5m");
        });
  }

  @Test
  void rejectsTimeoutAboveFiveMinutes() {
    runner.withPropertyValues("application.mail.read-timeout=10m")
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure()).rootCause()
              .hasMessageContaining("application.mail.read-timeout must be between 1ms and 5m");
        });
  }

  @Test
  void maxConcurrentSendsIsNoLongerAKey() {
    // Concurrency is bounded by application.async.email.max-size now; strict
    // binding must reject the old key instead of silently ignoring it.
    runner.withPropertyValues("application.mail.max-concurrent-sends=5")
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure()).rootCause()
              .hasMessageContaining("application.mail.max-concurrent-sends");
        });
  }
}
