package com.spring.app.shared.services;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Service;

import com.spring.app.configs.properties.JwtProperties;
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
  public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
    return claimsResolver.apply(parseClaims(token));
  }

  @Override
  public Claims parseClaims(String token) {
    return parser.parseSignedClaims(token).getPayload();
  }

  @Override
  public boolean isRefreshToken(String token) {
    return hasType(token, REFRESH_TOKEN_TYPE);
  }

  @Override
  public String generateToken(User user) {
    return buildToken(user, ACCESS_TOKEN_TYPE, jwtProperties.getExpiration());
  }

  @Override
  public String generateRefreshToken(User user) {
    return buildToken(user, REFRESH_TOKEN_TYPE, jwtProperties.getRefreshExpiration());
  }

  private String buildToken(User user, String tokenType, long expiration) {
    Map<String, Object> claims = getHashObject(user);
    claims.put(TOKEN_TYPE_CLAIM, tokenType);
    long now = System.currentTimeMillis();
    return Jwts.builder()
      .claims(claims)
      // Unique per token: iat/exp have second resolution, so two tokens minted
      // for one user in the same second would otherwise be byte-identical.
      .id(UUID.randomUUID().toString())
      .subject(user.getUsername())
      .issuedAt(new Date(now))
      .expiration(new Date(now + expiration))
      .signWith(signingKey, Jwts.SIG.HS256)
      .compact();
  }

  /**
   * No {@code email} claim (the subject already is the e-mail) and no
   * {@code status} claim (JwtFunctionUtil reads the live status from the
   * database): both only made the token longer.
   */
  private Map<String, Object> getHashObject(User user) {
    Map<String, Object> extraClaims = new HashMap<>();
    extraClaims.put("id", user.getId());
    extraClaims.put("role", user.getRole());
    return extraClaims;
  }

  private boolean hasType(String token, String expectedType) {
    try {
      return expectedType.equals(parseClaims(token).get(TOKEN_TYPE_CLAIM, String.class));
    } catch (JwtException | IllegalArgumentException e) {
      return false;
    }
  }
}
