package com.spring.app.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import com.spring.app.constants.WhitelistUrlConstant;
import com.spring.app.shared.interfaces.JwtServiceInterface;
import com.spring.app.shared.interfaces.RedisServiceInterface;
import com.spring.app.utils.JwtFunctionUtil;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthenticatorFilter extends OncePerRequestFilter {

  private final JwtServiceInterface jwtService;
  private final AntPathMatcher pathMatcher;
  private final JwtFunctionUtil jwtFunction;
  private final RedisServiceInterface redisService;

  @Override
  protected void doFilterInternal(@NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull FilterChain filterChain)
      throws ServletException, IOException {

    if (isPublicRoute(request)) {
      filterChain.doFilter(request, response);
      return;
    }

    String jwt = jwtFunction.extractTokenFromHeader(request);

    if (jwt != null && !redisService.isTokenBlacklisted(jwt)) {
      String username = jwtService.extractUsername(jwt);

      if (username != null && jwtFunction.isAuthenticationNotSet()) {
        jwtFunction.authenticateUser(jwt, username, request);
      }
    }

    filterChain.doFilter(request, response);
  }

  private boolean isPublicRoute(HttpServletRequest request) {
    String method = request.getMethod();
    String path = request.getRequestURI();

    for (String pattern : WhitelistUrlConstant.PUBLIC_URLS) {
      if (pathMatcher.match(pattern, path)) {
        return true;
      }
    }

    if ("GET".equalsIgnoreCase(method)) {
      for (String pattern : WhitelistUrlConstant.PUBLIC_GET_URLS) {
        if (pathMatcher.match(pattern, path)) {
          return true;
        }
      }
    }

    return false;
  }
}
