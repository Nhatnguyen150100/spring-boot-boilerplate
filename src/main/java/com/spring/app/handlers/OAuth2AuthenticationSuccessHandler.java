package com.spring.app.handlers;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.app.common.response.ResponseBuilder;
import com.spring.app.modules.auth.mapper.AuthMapper;
import com.spring.app.modules.auth.repositories.UserRepository;
import com.spring.app.shared.services.JwtService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class OAuth2AuthenticationSuccessHandler implements AuthenticationSuccessHandler {

  private final UserRepository userRepository;
  private final JwtService jwtService;
  private final AuthMapper authMapper;
  private final ObjectMapper objectMapper;

  /**
   * Handles the event of a successful OAuth2 authentication.
   *
   * <p>
   * This method is invoked when an authentication attempt using OAuth2 is
   * successful. It retrieves
   * the OAuth2 user information from the authentication principal and writes the
   * user's email as a JSON
   * response. If no user information is available, it responds with an
   * unauthorized error message.
   *
   * @param request        the HTTP request
   * @param response       the HTTP response
   * @param authentication the authentication object containing the OAuth2 user
   *                       details
   * @throws IOException if an input or output exception occurs
   */

  @Override
  public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
      Authentication authentication) throws IOException {

    OAuth2User oauth2User = (OAuth2User) authentication.getPrincipal();
    if (oauth2User == null) {
      response.setStatus(HttpStatus.UNAUTHORIZED.value());
      response.setContentType("application/json");
      response.getWriter().write("{\"error\": \"Authentication failed: No user information available\"}");
      return;
    }

    String email = oauth2User.getAttribute("email");
    if (email == null || email.isBlank()) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      response.setContentType("application/json");
      response.getWriter().write("{\"error\": \"OAuth2 provider did not return an email\"}");
      return;
    }

    var user = userRepository.findByEmail(email)
        .orElseThrow(() -> new IllegalStateException("User not found after OAuth2 authentication"));

    String accessToken = jwtService.generateToken(user);
    String refreshToken = jwtService.generateRefreshToken(user);

    var loginResponse = authMapper.userToLoginResponseDto(user, accessToken, refreshToken);

    response.setContentType("application/json");
    response.setStatus(HttpStatus.OK.value());
    objectMapper.writeValue(response.getWriter(), ResponseBuilder.success("Login successful", loginResponse));

    log.info("OAuth2 login success for user: {}", email);
  }

}
