package com.spring.app.configs;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.spring.app.configs.properties.ApplicationProperties;

class CorsConfigTest {

  private static CorsConfig corsConfig() {
    ApplicationProperties properties = new ApplicationProperties();
    properties.getCors().setAllowedOrigins(List.of("https://app.example.com"));
    properties.getCors().setMaxAgeSeconds(600);
    return new CorsConfig(properties);
  }

  @Test
  void allowsConfiguredOriginWithoutCredentials() {
    CorsConfiguration configuration = corsConfig().corsConfigurationSource()
        .getCorsConfiguration(new MockHttpServletRequest("GET", "/api/v1/users/me"));

    assertThat(configuration).isNotNull();
    assertThat(configuration.getAllowedOrigins()).containsExactly("https://app.example.com");
    assertThat(configuration.getAllowCredentials()).isFalse();
    assertThat(configuration.getAllowedMethods()).containsExactly("GET", "POST", "PUT", "DELETE", "OPTIONS");
    assertThat(configuration.getAllowedHeaders()).contains("Authorization", "Content-Type");
    assertThat(configuration.getMaxAge()).isEqualTo(600L);
    // A browser SPA can only read Retry-After on a 429 if it is exposed.
    assertThat(configuration.getExposedHeaders()).contains("Retry-After", "Content-Disposition");
  }

  @Test
  void corsIsDeclaredOnlyOnce() {
    assertThat(Arrays.stream(CorsConfig.class.getDeclaredMethods()).map(Method::getReturnType))
        .doesNotContain(WebMvcConfigurer.class);
  }
}
