package com.spring.app.configs;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

  /**
   * Provides a RedisConnectionFactory bean that establishes connections to a
   * Redis server.
   * 
   * @return A LettuceConnectionFactory instance for Redis connections.
   */
  @Bean
  RedisConnectionFactory redisConnectionFactory() {
    return new LettuceConnectionFactory();
  }

  /**
   * Provides a RedisTemplate bean configured for interaction with Redis
   * using JSON serialization for values and String serialization for keys.
   * This template is used to perform Redis operations with specified serializers
   * for keys and values, ensuring data consistency and compatibility.
   * 
   * @param connectionFactory The RedisConnectionFactory to establish
   *                          connections with the Redis server.
   * @return A RedisTemplate instance for Redis operations.
   */

  @Bean
  RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
    RedisTemplate<String, Object> template = new RedisTemplate<>();

    template.setConnectionFactory(connectionFactory);

    template.setKeySerializer(new StringRedisSerializer());
    template.setHashKeySerializer(new StringRedisSerializer());

    template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
    template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());

    template.afterPropertiesSet();
    return template;
  }
}
