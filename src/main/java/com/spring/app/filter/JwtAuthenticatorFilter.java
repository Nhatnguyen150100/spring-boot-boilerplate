package com.spring.app.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import com.spring.app.constants.WhitelistUrlConstant;
import com.spring.app.shared.services.JwtServiceInterface;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthenticatorFilter extends OncePerRequestFilter {

  private final JwtServiceInterface jwtService;
  private final UserDetailsService userDetailsService;
  private final AntPathMatcher pathMatcher;

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
  protected void doFilterInternal(HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain)
      throws ServletException, IOException {

    if (isPublicRoute(request)) {
      filterChain.doFilter(request, response);
      return;
    }

    String jwt = extractTokenFromHeader(request);

    if (jwt != null) {
      String username = jwtService.extractUsername(jwt);

      if (username != null && isAuthenticationNotSet()) {
        authenticateUser(jwt, username, request);
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

  /**
   * Extracts the JWT token from the Authorization header of the given HTTP
   * request.
   *
   * <p>
   * This method checks if the Authorization header is present and starts with
   * "Bearer ". If so, it returns the token part of the header by removing the
   * "Bearer " prefix. If the header is not present or does not start with
   * the specified prefix, the method returns null.
   *
   * @param request the HTTP request containing the Authorization header
   * @return the extracted JWT token, or null if the header is not present or
   *         invalid
   */

  private String extractTokenFromHeader(HttpServletRequest request) {
    final String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (authHeader != null && authHeader.startsWith("Bearer ")) {
      return authHeader.substring(7);
    }
    return null;
  }

  /**
   * Checks if the authentication in the security context is not set.
   *
   * @return true if the authentication is not set, false otherwise
   */
  private boolean isAuthenticationNotSet() {
    return SecurityContextHolder.getContext().getAuthentication() == null;
  }

  /**
   * Authenticates the user using the given JWT token and username.
   *
   * <p>
   * This method attempts to authenticate the user by loading the user details
   * using the given username, and then checking if the JWT token is valid for the
   * loaded user details. If the token is valid, a new
   * {@link UsernamePasswordAuthenticationToken} is created and set in the
   * security context.
   *
   * @param jwt      the JWT token to use for authentication
   * @param username the username to use for loading the user details
   * @param request  the HTTP request containing the authentication details
   */
  private void authenticateUser(String jwt, String username, HttpServletRequest request) {
    UserDetails userDetails = userDetailsService.loadUserByUsername(username);

    if (jwtService.isTokenValid(jwt, userDetails)) {
      UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(userDetails, null,
          userDetails.getAuthorities());

      authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
      SecurityContextHolder.getContext().setAuthentication(authToken);
    }
  }

}
