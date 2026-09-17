package com.spring.app.configs;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import lombok.extern.slf4j.Slf4j;

@Configuration
@Slf4j
public class RedisConfig {

  /**
   * Provides a RedisConnectionFactory bean that establishes connections to a
   * Redis server.
   *
   * @return A LettuceConnectionFactory instance for Redis connections.
   */
  @Bean
  RedisConnectionFactory redisConnectionFactory(RedisProperties redisProperties) {
    RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
    config.setHostName(redisProperties.getHost());
    config.setPort(redisProperties.getPort());
    config.setDatabase(redisProperties.getDatabase());
    if (redisProperties.getPassword() != null && !redisProperties.getPassword().isBlank()) {
      config.setPassword(RedisPassword.of(redisProperties.getPassword()));
    }
    return new LettuceConnectionFactory(config);
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

    // Use String serializer for keys
    template.setKeySerializer(new StringRedisSerializer());
    template.setHashKeySerializer(new StringRedisSerializer());

    // Use JSON serializer for values
    template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
    template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());

    template.afterPropertiesSet();

    return template;
  }

  /**
   * Verifies Redis is reachable, and stops startup if it is not.
   *
   * <p>
   * This deliberately lives outside {@link #redisTemplate} - a bean factory
   * method should build an object, not perform I/O. Keeping the check separate
   * also makes it switchable: {@code application.redis.fail-fast=false} lets
   * the context start without Redis, which is what the test profile needs.
   * </p>
   */
  @Bean
  @ConditionalOnProperty(name = "application.redis.fail-fast", havingValue = "true", matchIfMissing = true)
  ApplicationRunner redisConnectionCheck(RedisConnectionFactory connectionFactory) {
    return args -> {
      try (RedisConnection connection = connectionFactory.getConnection()) {
        connection.ping();
        log.info("Successfully connected to Redis");
      } catch (Exception e) {
        throw new IllegalStateException(
            "Redis is unreachable. Rate limiting, caching and refresh tokens all depend on it - "
                + "check REDIS_HOST/REDIS_PORT/REDIS_PASSWORD, or set application.redis.fail-fast=false "
                + "to start anyway.",
            e);
      }
    };
  }
}
