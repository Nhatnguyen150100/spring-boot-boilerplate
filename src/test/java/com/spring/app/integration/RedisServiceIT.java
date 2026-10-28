package com.spring.app.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.spring.app.shared.interfaces.RedisServiceInterface;

/**
 * RedisService's fixed-window Lua script and the Boot-managed connection, on
 * real Redis.
 */
class RedisServiceIT extends IntegrationTestBase {

  private static final long WINDOW_SECONDS = 60;

  @Autowired
  private RedisServiceInterface redisService;

  @Autowired
  private StringRedisTemplate stringRedisTemplate;

  @Autowired
  private LettuceConnectionFactory connectionFactory;

  @Autowired
  private RedisProperties redisProperties;

  @Test
  void firstIncrementOpensTheWindowWithItsTtl() {
    String key = newKey();

    assertThat(redisService.incrementRateLimit(key, WINDOW_SECONDS)).isEqualTo(1);
    assertThat(stringRedisTemplate.getExpire(key)).isBetween(1L, WINDOW_SECONDS);

    assertThat(redisService.incrementRateLimit(key, WINDOW_SECONDS)).isEqualTo(2);
    assertThat(stringRedisTemplate.opsForValue().get(key)).isEqualTo("2");
  }

  @Test
  void counterLeftWithoutTtlIsReArmed() {
    String key = newKey();
    // What the old two-command INCR/EXPIRE code could leave behind.
    stringRedisTemplate.opsForValue().set(key, "7");
    assertThat(stringRedisTemplate.getExpire(key)).isEqualTo(-1L);

    assertThat(redisService.incrementRateLimit(key, WINDOW_SECONDS)).isEqualTo(8);
    assertThat(stringRedisTemplate.getExpire(key)).isBetween(1L, WINDOW_SECONDS);
  }

  @Test
  void laterIncrementsNeverExtendALiveWindow() {
    String key = newKey();
    redisService.incrementRateLimit(key, WINDOW_SECONDS);
    // Pretend most of the window has passed.
    stringRedisTemplate.expire(key, Duration.ofSeconds(5));

    assertThat(redisService.incrementRateLimit(key, WINDOW_SECONDS)).isEqualTo(2);
    assertThat(stringRedisTemplate.getExpire(key)).isBetween(1L, 5L);
  }

  @Test
  void bootManagedConnectionAppliesYamlTimeoutsWithoutSslOrPassword() {
    // IntegrationTestBase overrides only host, port, password (blank) and
    // ssl.enabled (false); the timeouts are application.yml's.
    assertThat(redisProperties.getTimeout()).isNotNull().isPositive();
    assertThat(redisProperties.getConnectTimeout()).isNotNull().isPositive();
    assertThat(connectionFactory.getClientConfiguration().getCommandTimeout())
        .isEqualTo(redisProperties.getTimeout());
    assertThat(connectionFactory.getClientConfiguration().getClientOptions()).get()
        .satisfies(options -> assertThat(options.getSocketOptions().getConnectTimeout())
            .isEqualTo(redisProperties.getConnectTimeout()));
    assertThat(connectionFactory.isUseSsl()).isFalse();
    assertThat(connectionFactory.getPassword()).isNull();

    try (RedisConnection connection = connectionFactory.getConnection()) {
      assertThat(connection.ping()).isEqualTo("PONG");
    }
  }

  private static String newKey() {
    return "it:rate-limit:" + UUID.randomUUID();
  }
}
