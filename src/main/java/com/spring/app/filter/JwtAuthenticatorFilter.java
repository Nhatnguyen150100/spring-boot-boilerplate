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
import com.spring.app.utils.JwtFunctionUtil;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthenticatorFilter extends OncePerRequestFilter {

  private final JwtServiceInterface jwtService;
  private final AntPathMatcher pathMatcher;
  private final JwtFunctionUtil jwtFunction;

  /**
   * Filters incoming HTTP requests to authenticate JWT tokens.
   *
   * <p>
   * This method extracts the JWT token from the Authorization header of the
   * incoming request. If the token is present and starts with "Bearer ", it
   * validates the token and retrieves the username. If the token is valid and
   * the user is not already authenticated, the method sets the authentication
   * in the security context.
   *
   * @param request     the HTTP request
   * @param response    the HTTP response
   * @param filterChain the filter chain
   * @throws ServletException if an error occurs during the filtering process
   * @throws IOException      if an I/O error occurs
   */
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

    if (jwt != null) {
      String username = jwtService.extractUsername(jwt);

      if (username != null && jwtFunction.isAuthenticationNotSet()) {
        jwtFunction.authenticateUser(jwt, username, request);
      }
    }

    filterChain.doFilter(request, response);
  }

  /**
   * Checks if the incoming request is a public route that does not require
   * authentication.
   *
   * <p>
   * This method checks if the request path matches any of the public routes
   * defined in {@link WhitelistUrlConstant#PUBLIC_URLS}. If the request method
   * is GET, it also checks if the request path matches any of the public GET
   * routes defined in {@link WhitelistUrlConstant#PUBLIC_GET_URLS}.
   *
   * @param request the incoming HTTP request
   * @return true if the request is a public route, false otherwise
   */
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
