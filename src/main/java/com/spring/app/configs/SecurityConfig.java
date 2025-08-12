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
import com.spring.app.exceptions.CustomAuthenticationEntryPoint;
import com.spring.app.filter.JwtAuthenticatorFilter;
import com.spring.app.handlers.CustomAccessDeniedHandler;
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

  /**
   * Configures the Spring Security filter chain for the application.
   *
   * <p>
   * This method sets up the security filter chain that is used to protect the
   * application. It configures the following settings:
   * <ol>
   * <li>Enables CORS support with the configuration source defined in
   * {@link CorsConfigurationSource}.</li>
   * <li>Disables CSRF protection.</li>
   * <li>Configures exception handling settings defined in
   * {@link #exceptionHandling(ExceptionHandlingConfigurer)}.</li>
   * <li>Configures session management settings defined in
   * {@link #sessionManagement(SessionManagementConfigurer)}.</li>
   * <li>Configures authorization settings defined in
   * {@link #authorizeHttpRequests(AuthorizeHttpRequestsConfigurer)}.</li>
   * <li>Specifies the {@link AuthenticationProvider} to use for authentication.
   * </li>
   * <li>Adds the {@link JwtAuthenticatorFilter} to the filter chain before the
   * {@link UsernamePasswordAuthenticationFilter}.</li>
   * <li>Configures OAuth2 login settings defined in
   * {@link #oauth2Login(OAuth2LoginConfigurer)}.</li>
   * </ol>
   *
   * @param http The HttpSecurity object to configure.
   * @return The configured SecurityFilterChain.
   * @throws Exception If an error occurs while configuring the filter chain.
   */
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

  /**
   * Configures authorization settings for the application.
   *
   * <p>
   * This method sets up authorization rules for incoming HTTP requests. It
   * permits all incoming requests that match the public URLs defined in
   * {@link WhitelistUrlConstant#PUBLIC_URLS} and the public GET URLs defined in
   * {@link WhitelistUrlConstant#PUBLIC_GET_URLS}. For all other requests, it
   * requires authentication.
   *
   * @param auth The AuthorizationManagerRequestMatcherRegistry to configure.
   * @return The configured AuthorizationManagerRequestMatcherRegistry.
   */
  private AuthorizationManagerRequestMatcherRegistry authorizeHttpRequests(
      AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry auth) {
    return auth
        .requestMatchers(WhitelistUrlConstant.PUBLIC_URLS).permitAll()
        .requestMatchers(HttpMethod.GET, WhitelistUrlConstant.PUBLIC_GET_URLS).permitAll()
        .anyRequest().authenticated();
  }

  /**
   * Configures session management settings for the application.
   *
   * <p>
   * This method sets the session creation policy to STATELESS, meaning that
   * Spring Security will not create an HTTP session for the user. This is
   * important for a stateless API, as it prevents the server from having to
   * store session state for each user.
   *
   * @param session The SessionManagementConfigurer to configure.
   * @return The configured SessionManagementConfigurer.
   */
  private SessionManagementConfigurer<HttpSecurity> sessionManagement(
      SessionManagementConfigurer<HttpSecurity> session) {
    return session
        .sessionCreationPolicy(SessionCreationPolicy.STATELESS);
  }

  /**
   * Configures exception handling settings for the application.
   *
   * <p>
   * This method sets up custom handlers for authentication-related exceptions
   * such as access denied and authentication entry point.
   *
   * @param exception The ExceptionHandlingConfigurer to configure.
   * @return The configured ExceptionHandlingConfigurer.
   */

  private ExceptionHandlingConfigurer<HttpSecurity> exceptionHandling(
      ExceptionHandlingConfigurer<HttpSecurity> exception) {
    return exception.accessDeniedHandler(customAccessDeniedHandler)
        .authenticationEntryPoint(customAuthenticationEntryPoint);
  }

  /**
   * Configures OAuth2 login settings for the application.
   *
   * <p>
   * This method sets up the authorization endpoint, redirection endpoint, and
   * user info endpoint for OAuth2 login. It also specifies custom handlers for
   * authentication success and failure events.
   *
   * @param oauth2 The OAuth2LoginConfigurer to configure.
   * @return The configured OAuth2LoginConfigurer.
   */

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
