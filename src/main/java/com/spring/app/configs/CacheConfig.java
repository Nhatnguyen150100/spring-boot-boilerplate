package com.spring.app.configs;

import java.time.Duration;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.spring.app.configs.properties.CacheProperties;

import lombok.RequiredArgsConstructor;

@Configuration
@EnableCaching
@RequiredArgsConstructor
public class CacheConfig {

  private final CacheProperties cacheProperties;

  private static final int USERS_CACHE_TTL_MINUTES = 10;
  private static final int TOKENS_CACHE_TTL_MINUTES = 5;

  // Cache names
  public static final String USERS_CACHE = "users";
  public static final String USER_PROFILE = "userProfile";
  public static final String TOKENS_CACHE = "tokens";

  /**
   * Creates a RedisCacheManager to manage caches in Redis.
   *
   * <p>
   * Configuration for the cache manager is as follows:
   * </p>
   * <ul>
   * <li>Default TTL is {@value #DEFAULT_TTL_MINUTES} minutes.</li>
   * <li>Caches are serialized with a StringRedisSerializer for keys and a
   * GenericJackson2JsonRedisSerializer for values.</li>
   * <li>The cache named {@value #USERS_CACHE} has a TTL of
   * {@value #USERS_CACHE_TTL_MINUTES} minutes.</li>
   * <li>The cache named {@value #TOKENS_CACHE} has a TTL of
   * {@value #TOKENS_CACHE_TTL_MINUTES} minutes.</li>
   * </ul>
   */
  @Bean
  CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
    RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
        .entryTtl(Duration.ofMillis(cacheProperties.getTimeToLive()))
        .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
        .serializeValuesWith(
            RedisSerializationContext.SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer()));

    return RedisCacheManager.builder(connectionFactory)
        .cacheDefaults(defaultConfig)
        .withCacheConfiguration(USERS_CACHE,
            defaultConfig.entryTtl(Duration.ofMinutes(USERS_CACHE_TTL_MINUTES)))
        .withCacheConfiguration(USER_PROFILE,
            defaultConfig.entryTtl(Duration.ofMinutes(USERS_CACHE_TTL_MINUTES)))
        .withCacheConfiguration(TOKENS_CACHE,
            defaultConfig.entryTtl(Duration.ofMinutes(TOKENS_CACHE_TTL_MINUTES)))
        .build();
  }
}
