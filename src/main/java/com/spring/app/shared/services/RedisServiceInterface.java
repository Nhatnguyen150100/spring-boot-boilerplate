package com.spring.app.shared.services;

import java.util.concurrent.TimeUnit;

public interface RedisServiceInterface {
  public void setValue(String key, Object value, long duration, TimeUnit unit);

  public Object getValue(String key);

  public void delete(String key);

  public boolean hasKey(String key);
}
