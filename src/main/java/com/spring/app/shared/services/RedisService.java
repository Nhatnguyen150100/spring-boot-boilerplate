package com.spring.app.shared.services;

import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import com.spring.app.shared.interfaces.RedisServiceInterface;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class RedisService implements RedisServiceInterface {
  private final RedisTemplate<String, Object> redisTemplate;
  private final RedisTemplate<String, Object> rateLimitRedisTemplate;

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

  /**
   * Determines whether the given key exists in the Redis store.
   *
   * @param key the key for which to check existence
   * @return true if the key exists, false otherwise
   */
  @Override
  public boolean hasKey(String key) {
    return Boolean.TRUE.equals(redisTemplate.hasKey(key));
  }

  /**
   * Sets a value in the Redis store that is specific to rate limiting with the
   * given key, value, duration, and time unit. This value is associated with the
   * rate limit Redis connection, which is separate from the main Redis
   * connection.
   *
   * @param key      the key under which the value is stored
   * @param value    the value to store
   * @param duration the duration of the expiration
   * @param unit     the unit for the duration
   */
  @Override
  public void setRateLimitValue(String key, Object value, long duration, TimeUnit unit) {
    rateLimitRedisTemplate.opsForValue().set(key, value, duration, unit);
  }

  /**
   * Retrieves the value associated with the given key from the rate limit Redis
   * store. This store is separate from the main Redis connection.
   *
   * @param key the key from which to retrieve the value
   * @return the value associated with the given key, null if no such key exists
   */
  @Override
  public Object getRateLimitValue(String key) {
    return rateLimitRedisTemplate.opsForValue().get(key);
  }

  /**
   * Deletes the value associated with the given key from the rate limit Redis
   * store.
   *
   * @param key the key for which the value should be deleted
   */
  @Override
  public void deleteRateLimitKey(String key) {
    rateLimitRedisTemplate.delete(key);
  }

  /**
   * Determines whether the given key exists in the rate limit Redis store.
   * 
   * @param key the key for which to check existence
   * @return true if the key exists, false otherwise
   */
  @Override
  public boolean hasRateLimitKey(String key) {
    return Boolean.TRUE.equals(rateLimitRedisTemplate.hasKey(key));
  }
}
