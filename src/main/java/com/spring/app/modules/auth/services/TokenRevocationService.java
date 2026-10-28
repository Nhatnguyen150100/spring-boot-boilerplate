package com.spring.app.modules.auth.services;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.spring.app.configs.properties.JwtProperties;

import lombok.RequiredArgsConstructor;

/**
 * Ends every access token a user holds, e.g. after a password reset.
 *
 * <p>
 * Access tokens are stateless, so revoking them means remembering a
 * "not before" instant per user: {@code auth:tokens-not-before:<userId>} holds
 * the epoch second of the revocation, and a token whose {@code iat} is
 * earlier is refused. The key lives exactly as long as an access token can,
 * after which every token it could reject has expired anyway.
 * </p>
 *
 * <p>
 * Redis errors are not swallowed here: the writer surfaces them, and the
 * reader (JwtFunctionUtil) fails closed, like the logout blacklist.
 * </p>
 */
@Service
@RequiredArgsConstructor
public class TokenRevocationService {

  private static final String KEY_PREFIX = "auth:tokens-not-before:";

  private final StringRedisTemplate redisTemplate;
  private final JwtProperties jwtProperties;

  /** Every access token of {@code userId} issued before the current second stops working. */
  public void revokeAccessTokens(UUID userId) {
    redisTemplate.opsForValue().set(key(userId), String.valueOf(Instant.now().getEpochSecond()),
        Duration.ofMillis(jwtProperties.getExpiration()));
  }

  /**
   * True when the user's access tokens were revoked after {@code issuedAt}
   * (compared in whole seconds, the resolution of {@code iat}).
   *
   * @throws org.springframework.dao.DataAccessException when Redis cannot be read
   */
  public boolean isRevoked(UUID userId, Date issuedAt) {
    String notBefore = redisTemplate.opsForValue().get(key(userId));
    if (notBefore == null) {
      return false;
    }
    return issuedAt == null || issuedAt.toInstant().getEpochSecond() < Long.parseLong(notBefore);
  }

  private static String key(UUID userId) {
    return KEY_PREFIX + userId;
  }
}
