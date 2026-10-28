package com.spring.app.utils;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;

import com.spring.app.enums.EUserStatus;
import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.auth.services.TokenRevocationService;
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
  private final TokenRevocationService tokenRevocationService;

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
   * a refresh token, has been blacklisted by logout, belongs to an account
   * that is not ACTIVE or no longer exists (DELETED), or was issued before the
   * account's tokens were revoked (password reset or logout, see
   * {@link TokenRevocationService}). It also stays empty when the blacklist or
   * the revocation marker cannot be consulted (Redis down): failing open would
   * let a logged-out or reset token work again, so both checks fail closed.
   * A database failure while loading the user also leaves it anonymous.
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
   */
  public void authenticateAccessToken(String jwt, HttpServletRequest request) {
    Claims claims = jwtService.parseClaims(jwt);
    if (!JwtServiceInterface.ACCESS_TOKEN_TYPE.equals(
        claims.get(JwtServiceInterface.TOKEN_TYPE_CLAIM, String.class))) {
      return;
    }
    try {
      if (redisService.isTokenBlacklisted(jwt)) {
        return;
      }
    } catch (DataAccessException e) {
      log.warn("Token blacklist unavailable ({}); treating the request as anonymous", e.getClass().getSimpleName());
      return;
    }

    UserDetails userDetails;
    try {
      userDetails = userDetailsService.loadUserByUsername(claims.getSubject());
    } catch (UsernameNotFoundException e) {
      // The account no longer exists (or is DELETED): no longer a valid bearer.
      return;
    } catch (DataAccessException e) {
      // Database unreachable: anonymous (public endpoints still work, protected
      // ones get 401) rather than a 500 from the filter. Type only - the
      // driver's message can quote the e-mail.
      log.warn("User lookup failed ({}); treating the request as anonymous", e.getClass().getSimpleName());
      return;
    }
    if (!(userDetails instanceof User user) || user.getStatus() != EUserStatus.ACTIVE) {
      return;
    }
    try {
      if (tokenRevocationService.isRevoked(user.getId(), claims.getIssuedAt())) {
        return;
      }
    } catch (DataAccessException e) {
      log.warn("Token revocation marker unavailable ({}); treating the request as anonymous",
          e.getClass().getSimpleName());
      return;
    }

    UsernamePasswordAuthenticationToken authToken =
        new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
    SecurityContextHolder.getContext().setAuthentication(authToken);
  }
}
