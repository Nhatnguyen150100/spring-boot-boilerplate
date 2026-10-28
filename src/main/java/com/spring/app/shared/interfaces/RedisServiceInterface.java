package com.spring.app.shared.interfaces;

import java.util.concurrent.TimeUnit;

public interface RedisServiceInterface {
  void setValue(String key, Object value, long duration, TimeUnit unit);

  Object getValue(String key);

  void delete(String key);

  boolean hasKey(String key);

  void blacklistToken(String token, long expirySeconds);

  boolean isTokenBlacklisted(String token);

  /**
   * Stores {@code String.valueOf(value)} as a plain string in the rate-limit
   * keyspace.
   */
  void setRateLimitValue(String key, Object value, long duration, TimeUnit unit);

  /**
   * Increments a fixed-window rate-limit counter and returns the new value.
   * INCR and EXPIRE run as one Lua script, so the counter can never be left
   * without a TTL. The TTL is set when the window opens (and re-armed if the
   * key somehow has none), and a steady stream of requests does NOT extend it.
   *
   * @param key           the counter key
   * @param windowSeconds TTL of the window, applied when the window opens
   * @return the counter value after incrementing ({@code 0} if Redis returned
   *         no reply)
   */
  long incrementRateLimit(String key, long windowSeconds);

  /**
   * @return the raw counter as a {@code String}, or {@code null} if absent
   */
  Object getRateLimitValue(String key);

  void deleteRateLimitKey(String key);

  boolean hasRateLimitKey(String key);
}
