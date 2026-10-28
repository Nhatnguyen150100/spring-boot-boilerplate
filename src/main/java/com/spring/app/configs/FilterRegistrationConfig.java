package com.spring.app.configs;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.spring.app.filter.JwtAuthenticatorFilter;
import com.spring.app.filter.RateLimitFilter;

import jakarta.servlet.Filter;

/**
 * The application's filters are {@code @Component}s so they can be injected
 * into {@code SecurityConfig}, which adds them to the Spring Security chain.
 * Boot would ALSO register every {@code Filter} bean directly on the servlet
 * container; these disabled registrations prevent that second copy.
 */
@Configuration
public class FilterRegistrationConfig {

  @Bean
  FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
    return disabled(filter);
  }

  @Bean
  FilterRegistrationBean<JwtAuthenticatorFilter> jwtAuthenticatorFilterRegistration(JwtAuthenticatorFilter filter) {
    return disabled(filter);
  }

  private static <T extends Filter> FilterRegistrationBean<T> disabled(T filter) {
    FilterRegistrationBean<T> registration = new FilterRegistrationBean<>(filter);
    registration.setEnabled(false);
    return registration;
  }
}
