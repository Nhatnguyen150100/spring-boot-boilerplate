package com.spring.app.shared.services;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import javax.crypto.SecretKey;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import com.spring.app.configs.properties.JwtProperties;
import com.spring.app.enums.ERole;
import com.spring.app.enums.EUserStatus;
import com.spring.app.modules.auth.entities.User;
import com.spring.app.shared.interfaces.JwtServiceInterface;

@Service
public class JwtService implements JwtServiceInterface {

  private final JwtProperties jwtProperties;
  private final SecretKey signingKey;
  private final JwtParser parser;

  /**
   * Decodes the signing key once. {@link JwtProperties#isSecretKeyStrong()}
   * has already rejected a non-Base64 or short secret at startup;
   * {@link Keys#hmacShaKeyFor} re-checks the 256-bit floor for callers that
   * build the properties by hand.
   */
  public JwtService(JwtProperties jwtProperties) {
    this.jwtProperties = jwtProperties;
    this.signingKey = Keys.hmacShaKeyFor(Base64.getDecoder().decode(jwtProperties.getSecretKey().trim()));
    this.parser = Jwts.parser().verifyWith(signingKey).build();
  }

  /**
   * Retrieves the username from the given JWT token.
   *
   * @param token A JWT token.
   * @return The username from the token.
   */
  @Override
  public String extractUsername(String token) {
    return extractClaim(token, Claims::getSubject);
  }

  @Override
  public UUID extractUserId(String token) {
    return extractClaim(token, claims -> claims.get("id", UUID.class));
  }

  @Override
  public ERole extractRole(String token) {
    return extractClaim(token, claims -> claims.get("role", ERole.class));
  }

  @Override
  public EUserStatus extractUserStatus(String token) {
    return extractClaim(token, claims -> claims.get("status", EUserStatus.class));
  }

  @Override
  public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
    final Claims claims = extractAllClaims(token);
    return claimsResolver.apply(claims);
  }

  @Override
  public String generateToken(User user) {
    Map<String, Object> extraClaims = getHashObject(user);
    return buildToken(extraClaims, user, jwtProperties.getExpiration());
  }

  @Override
  public String generateRefreshToken(User user) {
    Map<String, Object> extraClaims = getHashObject(user);
    return buildToken(extraClaims, user, jwtProperties.getRefreshExpiration());
  }

  @Override
  public boolean isTokenValid(String token, UserDetails userDetails) {
    final String username = extractUsername(token);
    return (username.equals(userDetails.getUsername())) && !isTokenExpired(token);
  }

  @Override
  public long getRemainingExpirationSeconds(String token) {
    Date expiration = extractClaim(token, Claims::getExpiration);
    long remainingMs = expiration.getTime() - System.currentTimeMillis();
    return Math.max(0, remainingMs / 1000);
  }

  private String buildToken(Map<String, Object> extraClaims, UserDetails userDetails, long expiration) {
    return Jwts.builder()
      .claims(extraClaims)
      .subject(userDetails.getUsername())
      .issuedAt(new Date(System.currentTimeMillis()))
      .expiration(new Date(System.currentTimeMillis() + expiration))
      .signWith(signingKey, Jwts.SIG.HS256)
      .compact();
  }

  private Map<String, Object> getHashObject(User user) {
    Map<String, Object> extraClaims = new HashMap<>();
    extraClaims.put("id", user.getId());
    extraClaims.put("role", user.getRole());
    extraClaims.put("email", user.getEmail());
    extraClaims.put("status", user.getStatus());
    return extraClaims;
  }

  private boolean isTokenExpired(String token) {
    return extractClaim(token, Claims::getExpiration).before(new Date());
  }

  private Claims extractAllClaims(String token) {
    return parser.parseSignedClaims(token).getPayload();
  }
}
