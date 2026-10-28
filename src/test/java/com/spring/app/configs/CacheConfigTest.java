package com.spring.app.configs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.cache.interceptor.LoggingCacheErrorHandler;
import org.springframework.cache.transaction.TransactionAwareCacheDecorator;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import com.spring.app.configs.properties.CacheProperties;
import com.spring.app.modules.auth.dto.response.UserResponseDto;

/**
 * Exercises the exact serializer the cache manager hands to Redis, without a
 * Redis server.
 */
class CacheConfigTest {

  private static final UUID USER_ID = UUID.fromString("c0ffee00-1234-4abc-9def-001122334455");

  private final CacheConfig cacheConfig = new CacheConfig(new CacheProperties());

  @Test
  void userProfileEntriesRoundTripThroughJsonWithoutTypeMetadata() {
    UserResponseDto dto = UserResponseDto.builder()
        .id(USER_ID)
        .email("john@example.com")
        .fullName("Nguyễn Văn A")
        .phone("0987654321")
        .avatarUrl("https://example.com/a.png")
        .address("1 Main St")
        .dateOfBirth(LocalDate.of(1990, 1, 15))
        .status("ACTIVE")
        .description("hello")
        .role("USER")
        .createdAt("2026-01-01T00:00:00Z")
        .updatedAt("2026-01-02T00:00:00Z")
        .build();

    RedisCacheConfiguration config = userProfileConfig();
    byte[] json = toBytes(config.getValueSerializationPair().write(dto));
    Object restored = config.getValueSerializationPair().read(ByteBuffer.wrap(json));

    assertThat(restored).isEqualTo(dto);
    assertThat(new String(json, StandardCharsets.UTF_8))
        .contains("\"dateOfBirth\":\"1990-01-15\"")
        .doesNotContain("@class");
  }

  @Test
  void entriesWrittenByAnOlderBuildWithExtraFieldsStillDeserialize() {
    String json = "{\"id\":\"" + USER_ID + "\",\"email\":\"john@example.com\",\"removedField\":true}";

    Object restored = userProfileConfig().getValueSerializationPair()
        .read(ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)));

    assertThat(restored).isInstanceOf(UserResponseDto.class);
    assertThat(((UserResponseDto) restored).email()).isEqualTo("john@example.com");
  }

  @Test
  void userProfileUsesTheConfiguredTtlAndNeverCachesNull() {
    RedisCacheConfiguration config = userProfileConfig();

    assertThat(config.getTtlFunction().getTimeToLive("key", null)).isEqualTo(Duration.ofMinutes(10));
    assertThat(config.getAllowCacheNullValues()).isFalse();
  }

  @Test
  void onlyDeclaredCachesExistAndTheyAreTransactionAware() {
    RedisCacheManager manager = (RedisCacheManager) cacheConfig.cacheManager(mock(RedisConnectionFactory.class));
    manager.afterPropertiesSet();

    assertThat(manager.getCache(CacheConfig.USER_PROFILE)).isInstanceOf(TransactionAwareCacheDecorator.class);
    assertThat(manager.getCache("users")).isNull();
    assertThat(manager.getCache("tokens")).isNull();
  }

  @Test
  void keysAreNamespacedWithAVersionPrefix() {
    assertThat(userProfileConfig().getKeyPrefixFor(CacheConfig.USER_PROFILE))
        .isEqualTo("v2:userProfile::");
  }

  @Test
  void cacheErrorsAreLoggedNotThrown() {
    assertThat(cacheConfig.errorHandler()).isInstanceOf(LoggingCacheErrorHandler.class);
  }

  private RedisCacheConfiguration userProfileConfig() {
    RedisCacheConfiguration config = cacheConfig.cacheConfigurations().get(CacheConfig.USER_PROFILE);
    assertThat(config).isNotNull();
    return config;
  }

  private static byte[] toBytes(ByteBuffer buffer) {
    byte[] bytes = new byte[buffer.remaining()];
    buffer.get(bytes);
    return bytes;
  }
}
