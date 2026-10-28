package com.spring.app.handlers;

import java.io.IOException;

import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import com.spring.app.exceptions.UserNotActiveException;
import com.spring.app.modules.auth.dto.response.TokenResponseDto;
import com.spring.app.modules.auth.services.AuthServiceInterface;
import com.spring.app.shared.services.MonitoringService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;

/**
 * Issues the app's own tokens after a provider login and redirects the browser
 * to the frontend with them in the URL fragment. Token issuance (status check,
 * refresh-token storage) lives in {@link AuthServiceInterface}, not here.
 */
@Component
@Slf4j
public class OAuth2AuthenticationSuccessHandler implements AuthenticationSuccessHandler {

  private final AuthServiceInterface authService;
  private final OAuth2FrontendRedirect frontendRedirect;
  private final MonitoringService monitoringService;

  /**
   * {@code @Lazy} breaks a startup cycle: SecurityConfig needs this handler,
   * AuthServiceImpl needs the AuthenticationManager, and building that manager
   * instantiates SecurityConfig ({@code @EnableWebSecurity} implies
   * {@code @EnableGlobalAuthentication}). The service is resolved on the first
   * OAuth2 login instead.
   */
  public OAuth2AuthenticationSuccessHandler(@Lazy AuthServiceInterface authService,
      OAuth2FrontendRedirect frontendRedirect, MonitoringService monitoringService) {
    this.authService = authService;
    this.frontendRedirect = frontendRedirect;
    this.monitoringService = monitoringService;
  }

  @Override
  public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
      Authentication authentication) throws IOException {
    String target;
    try {
      String email = authentication.getPrincipal() instanceof OAuth2User user
          ? user.<String>getAttribute("email")
          : null;
      TokenResponseDto tokens = authService.issueTokensForOAuth2Login(email);
      target = frontendRedirect.success(tokens);
    } catch (UserNotActiveException e) {
      monitoringService.recordOAuth2Login(false);
      target = frontendRedirect.failure("account_not_active");
    } catch (RuntimeException e) {
      log.error("Could not issue tokens after an OAuth2 login", e);
      monitoringService.recordOAuth2Login(false);
      target = frontendRedirect.failure("server_error");
    }

    // The session only carried the OAuth2 handshake state; the API is stateless.
    HttpSession session = request.getSession(false);
    if (session != null) {
      session.invalidate();
    }
    response.sendRedirect(target);
  }
}
