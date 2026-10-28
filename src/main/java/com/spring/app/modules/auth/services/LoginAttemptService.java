package com.spring.app.modules.auth.services;

import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import com.spring.app.configs.properties.AuthProperties;
import com.spring.app.modules.auth.utils.EmailNormalizer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Counts FAILED logins per (e-mail, client IP) in Redis.
 *
 * <p>
 * The old throttle counted every login per e-mail alone, so anyone could lock
 * a user out by sending ~1000 requests with that address. Keying on the pair
 * means a stranger only locks out their own IP, and a successful login clears
 * the counter. The e-mail is hashed so addresses never appear in Redis keys.
 * </p>
 *
 * <p>
 * Redis errors fail open (the attempt is allowed), the same policy as the
 * per-IP rate limiter.
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoginAttemptService {

  private static final String KEY_PREFIX = "auth:login-failures:";

  /** INCR and arm the TTL in one round trip, so the key can never lose its expiry. */
  private static final RedisScript<Long> INCREMENT_WITH_TTL = new DefaultRedisScript<>(
      "local count = redis.call('INCR', KEYS[1]) "
          + "if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end "
          + "return count",
      Long.class);

  private final StringRedisTemplate redisTemplate;
  private final AuthProperties authProperties;

  public boolean isBlocked(String email, String clientIp) {
    try {
      String failures = redisTemplate.opsForValue().get(key(email, clientIp));
      return failures != null && Long.parseLong(failures) >= authProperties.getLoginMaxFailures();
    } catch (RuntimeException e) {
      log.warn("Login-failure counter unavailable, allowing the attempt: {}", e.getMessage());
      return false;
    }
  }

  public void recordFailure(String email, String clientIp) {
    try {
      redisTemplate.execute(INCREMENT_WITH_TTL, List.of(key(email, clientIp)),
          String.valueOf(authProperties.getLoginFailureWindow().toMillis()));
    } catch (RuntimeException e) {
      log.warn("Could not record a failed login: {}", e.getMessage());
    }
  }

  public void reset(String email, String clientIp) {
    try {
      redisTemplate.delete(key(email, clientIp));
    } catch (RuntimeException e) {
      log.warn("Could not clear the login-failure counter: {}", e.getMessage());
    }
  }

  static String key(String email, String clientIp) {
    return KEY_PREFIX + EmailNormalizer.fingerprint(email) + ":" + clientIp;
  }
}
