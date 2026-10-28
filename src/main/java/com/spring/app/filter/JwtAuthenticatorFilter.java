package com.spring.app.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.lang.NonNull;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.spring.app.utils.JwtFunctionUtil;

import io.jsonwebtoken.JwtException;

import java.io.IOException;

/**
 * Authenticates a request from its bearer access token, when there is one.
 *
 * <p>
 * This filter does not know which URLs are public. It never rejects a request
 * itself: an unusable token simply leaves the request anonymous, and the
 * rules in {@code SecurityConfig} (fed by {@code WhitelistUrlConstant}) then
 * decide - 401 through {@code CustomAuthenticationEntryPoint} on a protected
 * route, normal processing on a public one.
 * </p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticatorFilter extends OncePerRequestFilter {

  private final JwtFunctionUtil jwtFunction;

  @Override
  protected void doFilterInternal(@NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull FilterChain filterChain)
      throws ServletException, IOException {

    String jwt = jwtFunction.extractTokenFromHeader(request);

    if (jwt != null && jwtFunction.isAuthenticationNotSet()) {
      try {
        jwtFunction.authenticateAccessToken(jwt, request);
      } catch (JwtException | IllegalArgumentException | AuthenticationException e) {
        SecurityContextHolder.clearContext();
        log.debug("Ignoring unusable bearer token: {}", e.getClass().getSimpleName());
      }
    }

    filterChain.doFilter(request, response);
  }
}
