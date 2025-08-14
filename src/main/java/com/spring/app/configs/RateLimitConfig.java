package com.spring.app.configs;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RateLimitConfig {

  @Bean("rateLimitRedisTemplate")
  RedisTemplate<String, String> rateLimitRedisTemplate(RedisTemplate<String, Object> redisTemplate) {
    RedisTemplate<String, String> rateLimitTemplate = new RedisTemplate<>();
    rateLimitTemplate.setConnectionFactory(redisTemplate.getConnectionFactory());

    rateLimitTemplate.setKeySerializer(new StringRedisSerializer());
    rateLimitTemplate.setValueSerializer(new StringRedisSerializer());
    rateLimitTemplate.setHashKeySerializer(new StringRedisSerializer());
    rateLimitTemplate.setHashValueSerializer(new StringRedisSerializer());

    rateLimitTemplate.afterPropertiesSet();
    return rateLimitTemplate;
  }
}
