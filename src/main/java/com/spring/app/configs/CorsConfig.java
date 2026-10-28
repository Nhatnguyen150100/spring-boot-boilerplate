package com.spring.app.configs;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.spring.app.configs.properties.ApplicationProperties;

import lombok.RequiredArgsConstructor;

/**
 * The application's only CORS policy. Spring Security applies it
 * ({@code SecurityConfig} passes this source to {@code http.cors(...)}) before
 * Spring MVC sees the request, so there is deliberately no
 * {@code WebMvcConfigurer#addCorsMappings} - two sources drift apart.
 *
 * <p>
 * Credentials are not allowed: the API authenticates with a bearer token in
 * the {@code Authorization} header, never with cookies.
 * </p>
 */
@Configuration
@RequiredArgsConstructor
public class CorsConfig {

  private static final List<String> ALLOWED_METHODS = List.of(
      HttpMethod.GET.name(),
      HttpMethod.POST.name(),
      HttpMethod.PUT.name(),
      HttpMethod.DELETE.name(),
      HttpMethod.OPTIONS.name());

  private static final List<String> ALLOWED_HEADERS = List.of(
      "Authorization",
      "Content-Type",
      "Accept",
      "X-Requested-With",
      "Origin",
      "Cache-Control");

  private static final List<String> EXPOSED_HEADERS = List.of(
      "Authorization",
      "Content-Disposition",
      "X-Frame-Options");

  private final ApplicationProperties applicationProperties;

  @Bean
  CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(applicationProperties.getCors().getAllowedOrigins());
    configuration.setAllowedMethods(ALLOWED_METHODS);
    configuration.setAllowedHeaders(ALLOWED_HEADERS);
    configuration.setExposedHeaders(EXPOSED_HEADERS);
    configuration.setAllowCredentials(false);
    configuration.setMaxAge(applicationProperties.getCors().getMaxAgeSeconds());

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }
}
