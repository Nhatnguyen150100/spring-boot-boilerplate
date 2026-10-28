package com.spring.app.configs;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import lombok.extern.slf4j.Slf4j;

/**
 * Redis templates and the startup reachability check.
 *
 * <p>
 * There is deliberately no {@link RedisConnectionFactory} bean here. Spring
 * Boot's Lettuce auto-configuration builds it from {@code spring.data.redis.*}
 * and, unlike the hand-built factory this class used to declare, honours
 * {@code ssl.enabled}, {@code timeout}, {@code connect-timeout}, pooling and
 * (with {@code spring.threads.virtual.enabled}) the virtual-thread executor.
 * Boot also contributes the {@code StringRedisTemplate} that
 * {@code RedisService} uses for rate-limit counters.
 * </p>
 */
@Configuration
@Slf4j
public class RedisConfig {

  /**
   * Provides a RedisTemplate bean configured for interaction with Redis
   * using JSON serialization for values and String serialization for keys.
   * Its name ({@code redisTemplate}) makes Boot's own
   * {@code RedisTemplate<Object, Object>} back off.
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
                + "check REDIS_HOST/REDIS_PORT/REDIS_PASSWORD/REDIS_SSL_ENABLED, or set "
                + "application.redis.fail-fast=false to start anyway.",
            e);
      }
    };
  }
}
