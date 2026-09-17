package com.spring.app.configs;

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
   * <li>TTLs come from {@code application.cache.*} rather than constants, so
   * they can be tuned per environment.</li>
   * <li>Caches are serialized with a StringRedisSerializer for keys and a
   * GenericJackson2JsonRedisSerializer for values.</li>
   * </ul>
   */
  @Bean
  CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
    RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
        .entryTtl(cacheProperties.getDefaultTtl())
        .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
        .serializeValuesWith(
            RedisSerializationContext.SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer()));

    if (!cacheProperties.isCacheNullValues()) {
      defaultConfig = defaultConfig.disableCachingNullValues();
    }

    return RedisCacheManager.builder(connectionFactory)
        .cacheDefaults(defaultConfig)
        .withCacheConfiguration(USERS_CACHE,
            defaultConfig.entryTtl(cacheProperties.getUsersTtl()))
        .withCacheConfiguration(USER_PROFILE,
            defaultConfig.entryTtl(cacheProperties.getUsersTtl()))
        .withCacheConfiguration(TOKENS_CACHE,
            defaultConfig.entryTtl(cacheProperties.getTokensTtl()))
        .build();
  }
}
