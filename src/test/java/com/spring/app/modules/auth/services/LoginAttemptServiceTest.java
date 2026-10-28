package com.spring.app.modules.auth.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import com.spring.app.configs.properties.AuthProperties;

class LoginAttemptServiceTest {

  private static final String EMAIL = "user@example.com";
  private static final String IP = "203.0.113.7";

  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  @SuppressWarnings("unchecked")
  private final ValueOperations<String, String> values = mock(ValueOperations.class);
  private final AuthProperties properties = new AuthProperties();
  private final LoginAttemptService service = new LoginAttemptService(redis, properties);

  @BeforeEach
  void setUp() {
    when(redis.opsForValue()).thenReturn(values);
    properties.setLoginMaxFailures(3);
    properties.setLoginFailureWindow(Duration.ofMinutes(15));
  }

  @Test
  void keyCombinesAHashOfTheEmailWithTheClientIp() {
    String key = LoginAttemptService.key(EMAIL, IP);

    assertThat(key).startsWith("auth:login-failures:").endsWith(":" + IP).doesNotContain(EMAIL);
    assertThat(LoginAttemptService.key("USER@example.com", IP)).isEqualTo(key);
    assertThat(LoginAttemptService.key(EMAIL, "198.51.100.1")).isNotEqualTo(key);
  }

  @Test
  void notBlockedWithoutFailures() {
    assertThat(service.isBlocked(EMAIL, IP)).isFalse();
  }

  @Test
  void notBlockedBelowTheThreshold() {
    when(values.get(LoginAttemptService.key(EMAIL, IP))).thenReturn("2");

    assertThat(service.isBlocked(EMAIL, IP)).isFalse();
  }

  @Test
  void blockedAtTheThreshold() {
    when(values.get(LoginAttemptService.key(EMAIL, IP))).thenReturn("3");

    assertThat(service.isBlocked(EMAIL, IP)).isTrue();
  }

  @Test
  void recordFailureIncrementsWithTheWindowAsTtlInOneScript() {
    service.recordFailure(EMAIL, IP);

    verify(redis).execute(any(RedisScript.class), eq(List.of(LoginAttemptService.key(EMAIL, IP))), eq("900000"));
  }

  @Test
  void resetDeletesTheCounter() {
    service.reset(EMAIL, IP);

    verify(redis).delete(LoginAttemptService.key(EMAIL, IP));
  }

  @Test
  void redisOutageFailsOpen() {
    when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
    doThrow(new RedisConnectionFailureException("down")).when(redis).execute(any(RedisScript.class), anyList(), any());

    assertThat(service.isBlocked(EMAIL, IP)).isFalse();
    assertThatCode(() -> service.recordFailure(EMAIL, IP)).doesNotThrowAnyException();
  }
}
