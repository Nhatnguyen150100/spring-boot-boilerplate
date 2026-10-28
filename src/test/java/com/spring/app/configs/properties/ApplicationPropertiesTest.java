package com.spring.app.configs.properties;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@link ApplicationProperties} with {@code @Validated}. Strict binding
 * stays off for this class (it is the shared parent prefix), so only values
 * are checked here, not unknown keys.
 */
class ApplicationPropertiesTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(ApplicationProperties.class)
  static class Config {
  }

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withUserConfiguration(Config.class)
      .withPropertyValues(
          "application.frontend-url=https://app.example.com",
          "application.cors.allowed-origins=https://app.example.com");

  @Test
  void bindsAValidConfiguration() {
    runner.withPropertyValues("application.cors.allowed-origins=https://a.example.com,http://localhost:3000")
        .run(context -> {
          assertThat(context).hasNotFailed();
          ApplicationProperties props = context.getBean(ApplicationProperties.class);
          assertThat(props.getFrontendUrl()).isEqualTo("https://app.example.com");
          assertThat(props.getCors().getAllowedOrigins())
              .containsExactly("https://a.example.com", "http://localhost:3000");
        });
  }

  @ParameterizedTest
  @ValueSource(strings = { "", "   " })
  void rejectsABlankFrontendUrl(String value) {
    runner.withPropertyValues("application.frontend-url=" + value)
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure()).rootCause()
              .hasMessageContaining("application.frontend-url");
        });
  }

  @ParameterizedTest
  @ValueSource(strings = { "app.example.com", "ftp://app.example.com", "javascript:alert(1)", "https://", "//app.example.com" })
  void rejectsAFrontendUrlThatIsNotAnAbsoluteHttpUrl(String value) {
    runner.withPropertyValues("application.frontend-url=" + value)
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure()).rootCause()
              .hasMessageContaining("application.frontend-url must be an absolute http:// or https:// URL");
        });
  }

  /** The OAuth2 result is appended as path + #fragment; a query or fragment here would corrupt it. */
  @ParameterizedTest
  @ValueSource(strings = { "https://app.example.com/?next=x", "https://app.example.com?a=1", "https://app.example.com/#home",
      "https://app.example.com#x" })
  void rejectsAFrontendUrlWithAQueryOrFragment(String value) {
    runner.withPropertyValues("application.frontend-url=" + value)
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure()).rootCause()
              .hasMessageContaining("application.frontend-url must be an absolute http:// or https:// URL");
        });
  }

  @Test
  void acceptsAFrontendUrlWithAPort() {
    runner.withPropertyValues("application.frontend-url=http://localhost:3000")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @ParameterizedTest
  @ValueSource(strings = { "*", "https://ok.example.com,*", "https://*.example.com" })
  void rejectsAWildcardCorsOrigin(String origins) {
    runner.withPropertyValues("application.cors.allowed-origins=" + origins)
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure()).rootCause()
              .hasMessageContaining("application.cors.allowed-origins must not contain '*'");
        });
  }

  @Test
  void stillRequiresAtLeastOneCorsOrigin() {
    new ApplicationContextRunner().withUserConfiguration(Config.class)
        .withPropertyValues("application.frontend-url=https://app.example.com")
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure()).rootCause()
              .hasMessageContaining("application.cors.allowed-origins must contain at least one origin");
        });
  }
}
