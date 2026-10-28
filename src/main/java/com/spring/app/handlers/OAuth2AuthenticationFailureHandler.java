package com.spring.app.handlers;

import java.io.IOException;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import com.spring.app.shared.services.MonitoringService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Sends the browser back to the frontend with an error code in the URL
 * fragment. The exception message is never echoed: it can carry the e-mail or
 * provider internals.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class OAuth2AuthenticationFailureHandler implements AuthenticationFailureHandler {

  private final OAuth2FrontendRedirect frontendRedirect;
  private final MonitoringService monitoringService;

  @Override
  public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
      AuthenticationException exception) throws IOException {
    String errorCode = exception instanceof OAuth2AuthenticationException oauth2
        ? oauth2.getError().getErrorCode()
        : null;
    log.warn("OAuth2 login failed: {} ({})",
        OAuth2FrontendRedirect.safeErrorCode(errorCode), exception.getClass().getSimpleName());
    monitoringService.recordOAuth2Login(false);

    // The session only carried the OAuth2 handshake state; the API is stateless.
    HttpSession session = request.getSession(false);
    if (session != null) {
      session.invalidate();
    }
    response.sendRedirect(frontendRedirect.failure(errorCode));
  }
}
