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
import org.springframework.web.filter.OncePerRequestFilter;

import com.spring.app.shared.services.JwtServiceInterface;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthenticatorFilter extends OncePerRequestFilter {

  private final JwtServiceInterface jwtService;
  private final UserDetailsService userDetailsService;

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

    String jwt = extractTokenFromHeader(request);

    if (jwt != null) {
      String username = jwtService.extractUsername(jwt);

      if (username != null && isAuthenticationNotSet()) {
        authenticateUser(jwt, username, request);
      }
    }

    filterChain.doFilter(request, response);
  }

  private String extractTokenFromHeader(HttpServletRequest request) {
    final String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (authHeader != null && authHeader.startsWith("Bearer ")) {
      return authHeader.substring(7);
    }
    return null;
  }

  private boolean isAuthenticationNotSet() {
    return SecurityContextHolder.getContext().getAuthentication() == null;
  }

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
