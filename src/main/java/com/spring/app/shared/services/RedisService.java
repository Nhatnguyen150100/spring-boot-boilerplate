package com.spring.app.shared.services;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import com.spring.app.shared.interfaces.RedisServiceInterface;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Thin Redis facade.
 *
 * <p>
 * Two templates, chosen by type so Spring can never swap them:
 * </p>
 * <ul>
 * <li>{@code redisTemplate} ({@code RedisTemplate<String, Object>}, JSON
 * values): OTPs and the token blacklist.</li>
 * <li>{@code stringRedisTemplate} (Boot's {@link StringRedisTemplate}, plain
 * strings): rate-limit counters, which must be raw integers for INCR.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RedisService implements RedisServiceInterface {

  private static final String BLACKLIST_PREFIX = "blacklist:token:";

  /**
   * Fixed-window counter in one server-side step, so a counter can never exist
   * without a TTL. EXPIRE is applied when the window opens ({@code count == 1})
   * and also whenever the key has no TTL ({@code TTL == -1}), which re-arms
   * counters left without one by the old two-command INCR/EXPIRE code. A
   * steady stream of requests never extends a live window.
   */
  static final RedisScript<Long> INCREMENT_FIXED_WINDOW = RedisScript.of("""
      local count = redis.call('INCR', KEYS[1])
      if count == 1 or redis.call('TTL', KEYS[1]) == -1 then
        redis.call('EXPIRE', KEYS[1], ARGV[1])
      end
      return count
      """, Long.class);

  private final RedisTemplate<String, Object> redisTemplate;
  private final StringRedisTemplate stringRedisTemplate;

  /**
   * Sets a value in the Redis store with a TTL (Time-To-Live) that is
   * specified by the given duration and unit. The value is stored under the
   * given key.
   *
   * @param key      the key under which the value is stored
   * @param value    the value to store
   * @param duration the duration of the TTL
   * @param unit     the unit of time for the TTL
   */
  @Override
  public void setValue(String key, Object value, long duration, TimeUnit unit) {
    try {
      redisTemplate.opsForValue().set(key, value, duration, unit);
    } catch (Exception e) {
      log.error("Failed to set value in Redis for key: {}", key, e);
      throw new RuntimeException("Could not store value in Redis", e);
    }
  }

  /**
   * Retrieves the value that is associated with the given key from the Redis
   * store. If no value is associated with the key, null is returned.
   *
   * @param key the key for which the value is retrieved
   * @return the associated value, or null if no value is associated with the
   *         given key
   */
  @Override
  public Object getValue(String key) {
    return redisTemplate.opsForValue().get(key);
  }

  /**
   * Deletes the value associated with the given key from the Redis store.
   *
   * @param key the key for which the value should be deleted
   */
  @Override
  public void delete(String key) {
    redisTemplate.delete(key);
  }

  @Override
  public void blacklistToken(String token, long expirySeconds) {
    if (expirySeconds > 0) {
      redisTemplate.opsForValue().set(BLACKLIST_PREFIX + token, "1", expirySeconds, TimeUnit.SECONDS);
    }
  }

  @Override
  public boolean isTokenBlacklisted(String token) {
    return Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_PREFIX + token));
  }

  @Override
  public long incrementRateLimit(String key, long windowSeconds) {
    Long count = stringRedisTemplate.execute(INCREMENT_FIXED_WINDOW, List.of(key), String.valueOf(windowSeconds));
    return count == null ? 0L : count;
  }
}
