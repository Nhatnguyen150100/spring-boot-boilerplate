package com.spring.app.shared.interfaces;

import java.util.concurrent.TimeUnit;

public interface RedisServiceInterface {
  public void setValue(String key, Object value, long duration, TimeUnit unit);

  public Object getValue(String key);

  public void delete(String key);

  public boolean hasKey(String key);

  public void setRateLimitValue(String key, Object value, long duration, TimeUnit unit);

  public Object getRateLimitValue(String key);

  public void deleteRateLimitKey(String key);

  public boolean hasRateLimitKey(String key);
}
