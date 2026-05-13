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

  Object getRateLimitValue(String key);

  void deleteRateLimitKey(String key);

  boolean hasRateLimitKey(String key);
}
