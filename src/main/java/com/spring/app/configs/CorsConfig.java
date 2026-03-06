package com.spring.app.configs;

import java.util.Arrays;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.lang.NonNull;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.spring.app.configs.properties.ApplicationProperties;

import lombok.RequiredArgsConstructor;

@Configuration
@RequiredArgsConstructor
public class CorsConfig {

  private final ApplicationProperties applicationProperties;

  private List<String> getAllowedOrigins() {
    return Arrays.asList(applicationProperties.getFrontendUrl().split(","));
  }

  private List<String> getAllowedMethods() {
    return Arrays.asList(
        HttpMethod.GET.name(),
        HttpMethod.POST.name(),
        HttpMethod.PUT.name(),
        HttpMethod.DELETE.name(),
        HttpMethod.OPTIONS.name());
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();

    List<String> allowedOrigins = getAllowedOrigins();
    List<String> allowedMethods = getAllowedMethods();
    configuration.setAllowedOrigins(allowedOrigins);

    configuration.setAllowedMethods(allowedMethods);

    configuration.setAllowedHeaders(Arrays.asList(
        "Authorization",
        "Content-Type",
        "Accept",
        "X-Requested-With",
        "Origin",
        "Cache-Control"));

    configuration.setExposedHeaders(Arrays.asList(
        "Authorization",
        "Content-Disposition",
        "X-Frame-Options"));

    configuration.setAllowCredentials(true);
    configuration.setMaxAge(3600L);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }

  @Bean
  WebMvcConfigurer corsConfigurer() {
    return new WebMvcConfigurer() {
      @Override
      public void addCorsMappings(@NonNull CorsRegistry registry) {

        List<String> allowedOrigins = getAllowedOrigins();
        List<String> allowedMethods = getAllowedMethods();
        registry.addMapping("/**")
            .allowedOrigins(allowedOrigins.toArray(String[]::new))
            .allowedMethods(allowedMethods.toArray(String[]::new))
            .allowCredentials(true)
            .maxAge(3600);
      }
    };
  }
}