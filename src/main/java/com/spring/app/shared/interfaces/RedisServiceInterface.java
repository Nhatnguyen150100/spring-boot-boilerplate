package com.spring.app.shared.interfaces;

import java.util.concurrent.TimeUnit;

public interface RedisServiceInterface {
  void setValue(String key, Object value, long duration, TimeUnit unit);

  Object getValue(String key);

  void delete(String key);

  void blacklistToken(String token, long expirySeconds);

  boolean isTokenBlacklisted(String token);

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
}
