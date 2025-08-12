package com.spring.app.configs;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.config.annotation.web.configurers.ExceptionHandlingConfigurer;
import org.springframework.security.config.annotation.web.configurers.SessionManagementConfigurer;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer.AuthorizationManagerRequestMatcherRegistry;
import org.springframework.security.config.annotation.web.configurers.oauth2.client.OAuth2LoginConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

import com.spring.app.constants.WhitelistUrlConstant;
import com.spring.app.exceptions.CustomAccessDeniedHandler;
import com.spring.app.exceptions.CustomAuthenticationEntryPoint;
import com.spring.app.filter.JwtAuthenticatorFilter;
import com.spring.app.handlers.OAuth2AuthenticationFailureHandler;
import com.spring.app.handlers.OAuth2AuthenticationSuccessHandler;
import com.spring.app.shared.services.CustomOAuth2UserService;

import lombok.RequiredArgsConstructor;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

  private final JwtAuthenticatorFilter jwtAuthFilter;
  private final AuthenticationProvider authenticationProvider;
  private final CustomAccessDeniedHandler customAccessDeniedHandler;
  private final CustomAuthenticationEntryPoint customAuthenticationEntryPoint;
  private final CorsConfigurationSource corsConfigurationSource;
  private final CustomOAuth2UserService customOAuth2UserService;
  private final OAuth2AuthenticationSuccessHandler oAuth2AuthenticationSuccessHandler;
  private final OAuth2AuthenticationFailureHandler oAuth2AuthenticationFailureHandler;

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    return http
        .cors(cors -> cors.configurationSource(corsConfigurationSource))
        .csrf(CsrfConfigurer::disable)
        .exceptionHandling(exception -> exceptionHandling(exception))
        .sessionManagement(session -> sessionManagement(session))
        .authorizeHttpRequests(auth -> authorizeHttpRequests(auth))
        .authenticationProvider(authenticationProvider)
        .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
        .oauth2Login(oauth2 -> oauth2Login(oauth2))
        .build();
  }

  private AuthorizationManagerRequestMatcherRegistry authorizeHttpRequests(
      AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry auth) {
    return auth
        .requestMatchers(WhitelistUrlConstant.PUBLIC_URLS).permitAll()
        .requestMatchers(HttpMethod.GET, WhitelistUrlConstant.PUBLIC_GET_URLS).permitAll()
        .anyRequest().authenticated();
  }

  private SessionManagementConfigurer<HttpSecurity> sessionManagement(
      SessionManagementConfigurer<HttpSecurity> session) {
    return session
        .sessionCreationPolicy(SessionCreationPolicy.STATELESS);
  }

  private ExceptionHandlingConfigurer<HttpSecurity> exceptionHandling(
      ExceptionHandlingConfigurer<HttpSecurity> exception) {
    return exception.accessDeniedHandler(customAccessDeniedHandler)
        .authenticationEntryPoint(customAuthenticationEntryPoint);
  }

  private OAuth2LoginConfigurer<HttpSecurity> oauth2Login(OAuth2LoginConfigurer<HttpSecurity> oauth2) {
    return oauth2
        .authorizationEndpoint(authorization -> authorization
            .baseUri(WhitelistUrlConstant.OAUTH2_LOGIN_URL))
        .redirectionEndpoint(redirection -> redirection
            .baseUri(WhitelistUrlConstant.OAUTH2_REDIRECT_URL))
        .userInfoEndpoint(userInfo -> userInfo.userService(customOAuth2UserService))
        .successHandler(oAuth2AuthenticationSuccessHandler)
        .failureHandler(oAuth2AuthenticationFailureHandler);
  }

}
