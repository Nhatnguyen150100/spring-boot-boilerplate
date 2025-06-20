package com.spring.app.shared.services.impl;

import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import com.spring.app.shared.services.RedisServiceInterface;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RedisService implements RedisServiceInterface {
  private final RedisTemplate<String, Object> redisTemplate;

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
    redisTemplate.opsForValue().set(key, value, duration, unit);
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
}
