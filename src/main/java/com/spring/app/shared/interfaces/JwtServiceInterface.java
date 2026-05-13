package com.spring.app.shared.interfaces;

import java.util.UUID;
import java.util.function.Function;

import org.springframework.security.core.userdetails.UserDetails;

import com.spring.app.enums.ERole;
import com.spring.app.enums.EUserStatus;
import com.spring.app.modules.auth.entities.User;

import io.jsonwebtoken.Claims;

public interface JwtServiceInterface {
  String extractUsername(String token);

  UUID extractUserId(String token);

  ERole extractRole(String token);

  EUserStatus extractUserStatus(String token);

  <T> T extractClaim(String token, Function<Claims, T> claimsResolver);

  String generateToken(User user);

  String generateRefreshToken(User user);

  boolean isTokenValid(String token, UserDetails userDetails);

  long getRemainingExpirationSeconds(String token);
}
