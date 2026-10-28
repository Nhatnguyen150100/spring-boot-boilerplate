package com.spring.app.shared.services;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.spring.app.modules.auth.repositories.RefreshTokenRepository;

@ExtendWith(MockitoExtension.class)
class TokenCleanupServiceTest {

  @Mock
  private RefreshTokenRepository refreshTokenRepository;

  @Mock
  private StringRedisTemplate stringRedisTemplate;

  @Mock
  private ValueOperations<String, String> valueOperations;

  @InjectMocks
  private TokenCleanupService service;

  @BeforeEach
  void setUp() {
    when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
  }

  @Test
  void purgesWhenThisInstanceAcquiresTheLock() {
    when(valueOperations.setIfAbsent(eq("lock:token-cleanup"), anyString(), eq(Duration.ofMinutes(10))))
        .thenReturn(true);

    service.cleanupExpiredTokens();

    verify(refreshTokenRepository).deleteExpiredOrRevokedTokens(any(Instant.class));
  }

  @Test
  void skipsWhenAnotherInstanceHoldsTheLock() {
    when(valueOperations.setIfAbsent(eq("lock:token-cleanup"), anyString(), eq(Duration.ofMinutes(10))))
        .thenReturn(false);

    service.cleanupExpiredTokens();

    verifyNoInteractions(refreshTokenRepository);
  }

  @Test
  void skipsWhenRedisIsUnavailable() {
    when(valueOperations.setIfAbsent(eq("lock:token-cleanup"), anyString(), eq(Duration.ofMinutes(10))))
        .thenThrow(new RedisConnectionFailureException("Redis down"));

    service.cleanupExpiredTokens();

    verifyNoInteractions(refreshTokenRepository);
  }
}
