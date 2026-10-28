package com.spring.app.modules.auth.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import com.spring.app.configs.properties.AuthProperties;

class OtpAttemptServiceTest {

  private static final String EMAIL = "user@example.com";
  private static final String PURPOSE = "activation";

  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  private final AuthProperties properties = new AuthProperties();
  private final OtpAttemptService service = new OtpAttemptService(redis, properties);

  @BeforeEach
  void setUp() {
    properties.setOtpMaxAttempts(3);
  }

  private void givenCountAfterIncrement(long count) {
    doReturn(count).when(redis).execute(any(RedisScript.class), anyList(), any());
  }

  @Test
  void keyCombinesThePurposeWithAHashOfTheEmail() {
    String key = OtpAttemptService.key(EMAIL, PURPOSE);

    assertThat(key).startsWith("auth:otp-failures:activation:").doesNotContain(EMAIL);
    assertThat(OtpAttemptService.key(" USER@example.com ", PURPOSE)).isEqualTo(key);
    assertThat(OtpAttemptService.key(EMAIL, "password_reset")).isNotEqualTo(key);
  }

  @Test
  void aWrongOtpIsCountedForAsLongAsAnOtpLives() {
    givenCountAfterIncrement(1);

    assertThat(service.recordFailure(EMAIL, PURPOSE)).isFalse();

    verify(redis).execute(any(RedisScript.class), eq(List.of(OtpAttemptService.key(EMAIL, PURPOSE))), eq("180000"));
    verify(redis, never()).delete(anyString());
  }

  @Test
  void theLastAllowedGuessReportsTheCapAndClearsTheCounter() {
    givenCountAfterIncrement(3);

    assertThat(service.recordFailure(EMAIL, PURPOSE)).isTrue();

    verify(redis).delete(OtpAttemptService.key(EMAIL, PURPOSE));
  }

  @Test
  void resetDeletesTheCounter() {
    service.reset(EMAIL, PURPOSE);

    verify(redis).delete(OtpAttemptService.key(EMAIL, PURPOSE));
  }

  @Test
  void resetAllDeletesTheCountersOfBothPurposes() {
    service.resetAll(EMAIL);

    verify(redis).delete(List.of(OtpAttemptService.key(EMAIL, OtpAttemptService.PURPOSE_ACTIVATION),
        OtpAttemptService.key(EMAIL, OtpAttemptService.PURPOSE_PASSWORD_RESET)));
  }

  @Test
  void resetAllFailsOpen() {
    doThrow(new RedisConnectionFailureException("down")).when(redis).delete(anyList());

    assertThatCode(() -> service.resetAll(EMAIL)).doesNotThrowAnyException();
  }

  @Test
  void redisOutageFailsOpen() {
    doThrow(new RedisConnectionFailureException("down")).when(redis).execute(any(RedisScript.class), anyList(), any());
    doThrow(new RedisConnectionFailureException("down")).when(redis).delete(anyString());

    assertThat(service.recordFailure(EMAIL, PURPOSE)).isFalse();
    assertThatCode(() -> service.reset(EMAIL, PURPOSE)).doesNotThrowAnyException();
  }
}
