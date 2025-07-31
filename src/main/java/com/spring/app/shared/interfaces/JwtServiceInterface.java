package com.spring.app.shared.interfaces;

import java.util.UUID;
import java.util.function.Function;

import org.springframework.security.core.userdetails.UserDetails;

import com.spring.app.enums.ERole;
import com.spring.app.enums.EUserStatus;
import com.spring.app.modules.auth.entities.User;

import io.jsonwebtoken.Claims;

public interface JwtServiceInterface {
  public String extractUsername(String token);

  public UUID extractUserId(String token);

  public ERole extractRole(String token);

  public EUserStatus extractUserStatus(String token);

  public <T> T extractClaim(String token, Function<Claims, T> claimsResolver);

  public String generateToken(User user);

  public String generateRefreshToken(
      User user);

  public boolean isTokenValid(String token, UserDetails userDetails);
}
