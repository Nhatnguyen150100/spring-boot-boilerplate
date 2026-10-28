package com.spring.app.shared.interfaces;

import java.util.function.Function;

import org.springframework.security.core.userdetails.UserDetails;

import com.spring.app.modules.auth.entities.User;

import io.jsonwebtoken.Claims;

public interface JwtServiceInterface {

  /** Claim naming what a token may be used for. */
  String TOKEN_TYPE_CLAIM = "token_type";
  /** Bearer token for API calls. */
  String ACCESS_TOKEN_TYPE = "access";
  /** Only accepted by {@code POST /api/v1/auth/refresh-token}. */
  String REFRESH_TOKEN_TYPE = "refresh";

  String extractUsername(String token);

  <T> T extractClaim(String token, Function<Claims, T> claimsResolver);

  /**
   * Verifies signature and expiry and returns the claims.
   *
   * @throws io.jsonwebtoken.JwtException  for an expired, forged or malformed token
   * @throws IllegalArgumentException      for a null or empty token
   */
  Claims parseClaims(String token);

  /** True only for a valid, unexpired token of type {@code access}. Never throws. */
  boolean isAccessToken(String token);

  /** True only for a valid, unexpired token of type {@code refresh}. Never throws. */
  boolean isRefreshToken(String token);

  String generateToken(User user);

  String generateRefreshToken(User user);

  boolean isTokenValid(String token, UserDetails userDetails);

  long getRemainingExpirationSeconds(String token);
}
