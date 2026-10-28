package com.spring.app.shared.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Pure Mockito: no Redis server. What Redis does with the Lua script is
 * covered by the opt-in Testcontainers IT (P6).
 */
@ExtendWith(MockitoExtension.class)
class RedisServiceTest {

  private static final String KEY = "rate_limit:AUTH:minute:203.0.113.7";

  @Mock
  private RedisTemplate<String, Object> redisTemplate;

  @Mock
  private StringRedisTemplate stringRedisTemplate;

  @Mock
  private ValueOperations<String, String> stringValueOps;

  @Captor
  private ArgumentCaptor<RedisScript<Long>> scriptCaptor;

  private RedisService redisService;

  @BeforeEach
  void setUp() {
    redisService = new RedisService(redisTemplate, stringRedisTemplate);
  }

  @Test
  void incrementRunsIncrAndExpireAsOneScriptOnTheStringTemplate() {
    when(stringRedisTemplate.execute(ArgumentMatchers.<RedisScript<Long>>any(), eq(List.of(KEY)), eq("60")))
        .thenReturn(1L);

    long count = redisService.incrementRateLimit(KEY, 60);

    assertThat(count).isEqualTo(1L);
    verify(stringRedisTemplate).execute(scriptCaptor.capture(), eq(List.of(KEY)), eq("60"));
    RedisScript<Long> script = scriptCaptor.getValue();
    assertThat(script.getResultType()).isEqualTo(Long.class);
    assertThat(script.getScriptAsString())
        .contains("redis.call('INCR', KEYS[1])")
        .contains("redis.call('EXPIRE', KEYS[1], ARGV[1])")
        .contains("redis.call('TTL', KEYS[1]) == -1");

    // No separate round trips that could leave a counter without a TTL.
    verify(stringRedisTemplate, never()).opsForValue();
    verify(stringRedisTemplate, never()).expire(anyString(), anyLong(), any(TimeUnit.class));
    verifyNoInteractions(redisTemplate);
  }

  @Test
  void incrementTreatsANullReplyAsZero() {
    when(stringRedisTemplate.execute(ArgumentMatchers.<RedisScript<Long>>any(), eq(List.of(KEY)), eq("3600")))
        .thenReturn((Long) null);

    assertThat(redisService.incrementRateLimit(KEY, 3600)).isZero();
  }

  @Test
  void rateLimitValuesAreWrittenAndReadAsPlainStrings() {
    when(stringRedisTemplate.opsForValue()).thenReturn(stringValueOps);
    when(stringValueOps.get(KEY)).thenReturn("7");

    redisService.setRateLimitValue(KEY, 5, 60, TimeUnit.SECONDS);

    verify(stringValueOps).set(KEY, "5", 60, TimeUnit.SECONDS);
    assertThat(redisService.getRateLimitValue(KEY)).isEqualTo("7");
    verifyNoInteractions(redisTemplate);
  }
}
