package com.spring.app.utils;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;

import com.spring.app.shared.interfaces.JwtServiceInterface;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
@RequiredArgsConstructor
public class JwtFunctionUtil {

  private final JwtServiceInterface jwtService;
  private final UserDetailsService userDetailsService;

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
  public String extractTokenFromHeader(HttpServletRequest request) {
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
  public boolean isAuthenticationNotSet() {
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
  public void authenticateUser(String jwt, String username, HttpServletRequest request) {
    UserDetails userDetails = userDetailsService.loadUserByUsername(username);

    if (jwtService.isTokenValid(jwt, userDetails)) {
      UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(userDetails, null,
          userDetails.getAuthorities());

      authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
      SecurityContextHolder.getContext().setAuthentication(authToken);
    }
  }
}
