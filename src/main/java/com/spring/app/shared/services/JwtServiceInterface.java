package com.spring.app.shared.services;

import java.util.function.Function;

import org.springframework.security.core.userdetails.UserDetails;

import com.spring.app.modules.auth.entities.User;

import io.jsonwebtoken.Claims;

public interface JwtServiceInterface {
  public String extractUsername(String token);

  public String extractUserId(String token);

  public String extractRole(String token);

  public <T> T extractClaim(String token, Function<Claims, T> claimsResolver);

  public String generateToken(User user);

  public String generateRefreshToken(
      User user);

  public boolean isTokenValid(String token, UserDetails userDetails);
}
