package com.spring.app.configs;

import java.time.Duration;
import java.util.Map;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.spring.app.configs.properties.CacheProperties;
import com.spring.app.modules.auth.dto.response.UserResponseDto;

import lombok.RequiredArgsConstructor;

/**
 * Redis cache manager.
 *
 * <p>
 * Rules:
 * </p>
 * <ul>
 * <li>Only immutable DTOs are cached, never JPA entities or
 * {@code ResponseEntity} (guarded by {@code CachedReturnTypesTest}).</li>
 * <li>Every cache is declared in {@link #cacheConfigurations()} with a
 * serializer typed to its value class, so no {@code @class} type metadata is
 * written and nothing is deserialized into an unexpected type. Undeclared cache
 * names are refused at runtime ({@code Cannot find cache named ...}). To add a
 * cache, add an entry there.</li>
 * <li>The JSON mapper has JavaTimeModule (ISO dates) and ignores unknown
 * properties, so entries written by an older build stay readable after a DTO
 * loses a field.</li>
 * <li>Puts and evicts are transaction-aware: inside a transaction they run
 * after commit, so an evict cannot be undone by a reader that saw pre-commit
 * data.</li>
 * <li>{@code null} is never cached.</li>
 * </ul>
 */
@Configuration
@EnableCaching
@RequiredArgsConstructor
public class CacheConfig {

  /** {@link UserResponseDto} by user id ({@code UUID}). */
  public static final String USER_PROFILE = "userProfile";

  private static final ObjectMapper CACHE_OBJECT_MAPPER = JsonMapper.builder()
      .addModule(new JavaTimeModule())
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .build();

  private final CacheProperties cacheProperties;

  @Bean
  CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
    return RedisCacheManager.builder(connectionFactory)
        .withInitialCacheConfigurations(cacheConfigurations())
        .disableCreateOnMissingCache()
        .transactionAware()
        .build();
  }

  /** One entry per cache; nothing else may be cached. */
  Map<String, RedisCacheConfiguration> cacheConfigurations() {
    return Map.of(
        USER_PROFILE, typedConfiguration(UserResponseDto.class, cacheProperties.getUsersTtl()));
  }

  private static <T> RedisCacheConfiguration typedConfiguration(Class<T> valueType, Duration ttl) {
    return RedisCacheConfiguration.defaultCacheConfig()
        .entryTtl(ttl)
        .disableCachingNullValues()
        .serializeKeysWith(SerializationPair.fromSerializer(new StringRedisSerializer()))
        .serializeValuesWith(SerializationPair.fromSerializer(
            new Jackson2JsonRedisSerializer<>(CACHE_OBJECT_MAPPER, valueType)));
  }
}
