package com.spring.app.handlers;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.app.modules.auth.mapper.AuthMapper;
import com.spring.app.modules.user.services.UserServiceInterface;
import com.spring.app.modules.user.services.impl.UserService;
import com.spring.app.shared.services.JwtService;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class OAuth2AuthenticationSuccessHandler implements AuthenticationSuccessHandler {

  private final UserServiceInterface userService;
  private final JwtService jwtService;
  private final AuthMapper authMapper;
  private final ObjectMapper objectMapper;

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
    String name = oauth2User.getAttribute("name");
    String googleId = oauth2User.getAttribute("sub");
    String avatar = oauth2User.getAttribute("picture");

    response.setContentType("application/json");
    response.setStatus(HttpStatus.OK.value());
    response.getWriter().write(objectMapper.writeValueAsString(email));
  }
}
