package com.spring.app.utils;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;

import com.spring.app.enums.EUserStatus;
import com.spring.app.modules.auth.entities.User;
import com.spring.app.shared.interfaces.JwtServiceInterface;
import com.spring.app.shared.interfaces.RedisServiceInterface;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
@RequiredArgsConstructor
public class JwtFunctionUtil {

  private final JwtServiceInterface jwtService;
  private final UserDetailsService userDetailsService;
  private final RedisServiceInterface redisService;

  /**
   * Extracts the JWT token from the Authorization header of the given HTTP
   * request.
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
   * Authenticates the request from a bearer access token.
   *
   * <p>
   * The context is left empty (the request stays anonymous) when the token is
   * a refresh token, has been blacklisted by logout, or belongs to an account
   * that is not ACTIVE.
   * </p>
   *
   * <p>
   * This loads the user from the database on every authenticated request. That
   * is deliberate: a status change (INACTIVE, DELETED) takes effect on the next
   * request instead of when the token expires.
   * </p>
   *
   * @throws io.jsonwebtoken.JwtException for an expired, forged or malformed token
   * @throws IllegalArgumentException      for an empty token
   * @throws org.springframework.security.core.userdetails.UsernameNotFoundException
   *                                       when the subject no longer exists
   */
  public void authenticateAccessToken(String jwt, HttpServletRequest request) {
    Claims claims = jwtService.parseClaims(jwt);
    if (!JwtServiceInterface.ACCESS_TOKEN_TYPE.equals(
        claims.get(JwtServiceInterface.TOKEN_TYPE_CLAIM, String.class))) {
      return;
    }
    if (redisService.isTokenBlacklisted(jwt)) {
      return;
    }

    UserDetails userDetails = userDetailsService.loadUserByUsername(claims.getSubject());
    if (!(userDetails instanceof User user) || user.getStatus() != EUserStatus.ACTIVE) {
      return;
    }

    UsernamePasswordAuthenticationToken authToken =
        new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
    SecurityContextHolder.getContext().setAuthentication(authToken);
  }
}
