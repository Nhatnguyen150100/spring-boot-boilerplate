package com.spring.app.modules.auth.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.spring.app.configs.properties.JwtProperties;

class TokenRevocationServiceTest {

  private static final UUID USER_ID = UUID.fromString("3f8f67d6-88a1-4dc4-8921-2f7f1d3b9a10");
  private static final String KEY = "auth:tokens-not-before:" + USER_ID;

  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  @SuppressWarnings("unchecked")
  private final ValueOperations<String, String> values = mock(ValueOperations.class);
  private final JwtProperties jwtProperties = new JwtProperties();
  private final TokenRevocationService service = new TokenRevocationService(redis, jwtProperties);

  @BeforeEach
  void setUp() {
    when(redis.opsForValue()).thenReturn(values);
    jwtProperties.setExpiration(900_000);
  }

  private static Date secondsFromNow(long seconds) {
    return Date.from(Instant.now().plusSeconds(seconds));
  }

  @Test
  void revokingStoresTheCurrentSecondForAsLongAsAnAccessTokenLives() {
    long before = Instant.now().getEpochSecond();

    service.revokeAccessTokens(USER_ID);

    ArgumentCaptor<String> notBefore = ArgumentCaptor.forClass(String.class);
    verify(values).set(eq(KEY), notBefore.capture(), eq(Duration.ofMillis(900_000)));
    assertThat(Long.parseLong(notBefore.getValue())).isBetween(before, Instant.now().getEpochSecond());
  }

  @Test
  void withoutAMarkerNothingIsRevoked() {
    assertThat(service.isRevoked(USER_ID, secondsFromNow(-3600))).isFalse();
  }

  @Test
  void aTokenIssuedBeforeTheMarkerIsRevokedAndOneIssuedLaterIsNot() {
    when(values.get(KEY)).thenReturn(String.valueOf(Instant.now().getEpochSecond()));

    assertThat(service.isRevoked(USER_ID, secondsFromNow(-60))).isTrue();
    assertThat(service.isRevoked(USER_ID, secondsFromNow(60))).isFalse();
  }

  @Test
  void aTokenWithoutIssuedAtIsRevokedOnceAMarkerExists() {
    when(values.get(KEY)).thenReturn(String.valueOf(Instant.now().getEpochSecond()));

    assertThat(service.isRevoked(USER_ID, null)).isTrue();
  }

  @Test
  void redisErrorsReachTheCallerWhichFailsClosed() {
    when(values.get(KEY)).thenThrow(new RedisConnectionFailureException("down"));

    assertThatThrownBy(() -> service.isRevoked(USER_ID, new Date())).isInstanceOf(DataAccessException.class);
  }
}
