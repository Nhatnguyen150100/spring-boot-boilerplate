package com.spring.app.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import com.spring.app.configs.properties.ApplicationProperties;
import com.spring.app.configs.properties.AuthProperties;
import com.spring.app.exceptions.UserNotActiveException;
import com.spring.app.modules.auth.dto.response.TokenResponseDto;
import com.spring.app.modules.auth.services.AuthServiceInterface;
import com.spring.app.shared.services.MonitoringService;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class OAuth2HandlersTest {

  private static final String CALLBACK = "https://app.example.com/oauth2/callback";

  private final ApplicationProperties applicationProperties = new ApplicationProperties();
  private final AuthProperties authProperties = new AuthProperties();
  private final OAuth2FrontendRedirect redirect = new OAuth2FrontendRedirect(applicationProperties, authProperties);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final MonitoringService monitoringService = new MonitoringService(meterRegistry);
  private final AuthServiceInterface authService = mock(AuthServiceInterface.class);
  private final OAuth2AuthenticationSuccessHandler successHandler =
      new OAuth2AuthenticationSuccessHandler(authService, redirect, monitoringService);
  private final OAuth2AuthenticationFailureHandler failureHandler =
      new OAuth2AuthenticationFailureHandler(redirect, monitoringService);

  private final MockHttpServletRequest request = new MockHttpServletRequest();
  private final MockHttpServletResponse response = new MockHttpServletResponse();

  @BeforeEach
  void setUp() {
    applicationProperties.setFrontendUrl("https://app.example.com/");
  }

  private static Authentication oauth2Login(String email) {
    OAuth2User principal = new DefaultOAuth2User(
        List.of(new SimpleGrantedAuthority("ROLE_USER")), Map.of("email", email), "email");
    return new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google");
  }

  private double oauth2Count(String result) {
    Counter counter = meterRegistry.find("auth.oauth2.login").tags("result", result).counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  void successRedirectsToTheFrontendWithTokensInTheFragment() throws Exception {
    when(authService.issueTokensForOAuth2Login("user@example.com"))
        .thenReturn(new TokenResponseDto("access.jwt", "refresh.jwt"));
    request.getSession(true);

    successHandler.onAuthenticationSuccess(request, response, oauth2Login("user@example.com"));

    assertThat(response.getRedirectedUrl())
        .isEqualTo(CALLBACK + "#access_token=access.jwt&refresh_token=refresh.jwt&token_type=Bearer");
    assertThat(response.getContentAsString()).isEmpty();
    assertThat(request.getSession(false)).as("OAuth2 handshake session dropped").isNull();
  }

  @Test
  void successForANonActiveAccountRedirectsWithAnErrorAndNoTokens() throws Exception {
    when(authService.issueTokensForOAuth2Login(any())).thenThrow(new UserNotActiveException("Account is not active"));

    successHandler.onAuthenticationSuccess(request, response, oauth2Login("pending@example.com"));

    assertThat(response.getRedirectedUrl()).isEqualTo(CALLBACK + "#error=account_not_active");
    assertThat(oauth2Count("failure")).isEqualTo(1.0);
  }

  @Test
  void failureRedirectsWithTheErrorCodeAndNeverTheMessage() throws Exception {
    failureHandler.onAuthenticationFailure(request, response,
        new OAuth2AuthenticationException(new OAuth2Error("email_not_verified"), "detail victim@example.com"));

    assertThat(response.getRedirectedUrl()).isEqualTo(CALLBACK + "#error=email_not_verified");
    assertThat(response.getContentAsString()).doesNotContain("victim");
    assertThat(oauth2Count("failure")).isEqualTo(1.0);
  }

  @Test
  void failureWithAnUnexpectedCodeFallsBackToAGenericOne() throws Exception {
    failureHandler.onAuthenticationFailure(request, response,
        new OAuth2AuthenticationException(new OAuth2Error("<script>")));

    assertThat(response.getRedirectedUrl()).isEqualTo(CALLBACK + "#error=authentication_failed");
  }

  @Test
  void nonOAuth2FailureUsesTheGenericCode() throws Exception {
    failureHandler.onAuthenticationFailure(request, response, new BadCredentialsException("secret"));

    assertThat(response.getRedirectedUrl()).isEqualTo(CALLBACK + "#error=authentication_failed");
  }
}
