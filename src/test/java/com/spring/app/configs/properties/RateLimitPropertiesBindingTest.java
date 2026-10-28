package com.spring.app.configs.properties;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.UnboundConfigurationPropertiesException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@code application.rate-limit} from the real {@code application.yml}
 * through Boot's own binder, so {@code ignoreUnknownFields = false} is the
 * behaviour under test, without starting the application.
 */
class RateLimitPropertiesBindingTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(RateLimitProperties.class)
  static class Config {
  }

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withInitializer(new ConfigDataApplicationContextInitializer())
      .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
      .withUserConfiguration(Config.class);

  @Test
  void applicationYmlRateLimitSectionBindsStrictly() {
    runner.run(context -> {
      assertThat(context).hasNotFailed();
      RateLimitProperties properties = context.getBean(RateLimitProperties.class);
      assertThat(properties.getApi().getRequestsPerMinute()).isEqualTo(60);
      assertThat(properties.getAuth().getRequestsPerMinute()).isEqualTo(10);
      assertThat(properties.getUpload().getRequestsPerMinute()).isEqualTo(5);
      assertThat(properties.getGlobal().getRequestsPerMinute()).isEqualTo(100);
    });
  }

  @Test
  void burstCapacityIsNoLongerAKnownKey() {
    runner.withPropertyValues("application.rate-limit.api.burst-capacity=5").run(context -> {
      assertThat(context).hasFailed();
      assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(UnboundConfigurationPropertiesException.class)
          .hasStackTraceContaining("burst-capacity");
    });
  }

  @Test
  void strictBindingStillRejectsOtherUnknownKeys() {
    runner.withPropertyValues("application.rate-limit.api.requests-per-minut=5").run(context -> {
      assertThat(context).hasFailed();
      assertThat(context.getStartupFailure()).hasStackTraceContaining("requests-per-minut");
    });
  }
}
