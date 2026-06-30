package com.spring.app.shared.interfaces;

import java.util.concurrent.TimeUnit;

public interface RedisServiceInterface {
  void setValue(String key, Object value, long duration, TimeUnit unit);

  Object getValue(String key);

  void delete(String key);

  boolean hasKey(String key);

  void blacklistToken(String token, long expirySeconds);

  boolean isTokenBlacklisted(String token);

  void setRateLimitValue(String key, Object value, long duration, TimeUnit unit);

  /**
   * Atomically increments a fixed-window rate-limit counter and returns the new
   * value. The window expiry is applied only when the counter is first created
   * (value == 1), so a steady stream of requests does NOT keep sliding the TTL.
   *
   * @param key           the counter key
   * @param windowSeconds TTL of the window, applied on first increment
   * @return the counter value after incrementing
   */
  long incrementRateLimit(String key, long windowSeconds);

  Object getRateLimitValue(String key);

  void deleteRateLimitKey(String key);

  boolean hasRateLimitKey(String key);
}
