package com.spring.app.shared.services;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.spring.app.modules.auth.repositories.RefreshTokenRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Nightly purge of expired or revoked refresh tokens.
 *
 * <p>
 * Every instance schedules the job, so a Redis lock ({@code SET NX EX}) lets
 * exactly one of them run it. The lock is not released when the purge ends; it
 * expires after {@link #LOCK_TTL}. An instance whose scheduler fires a few
 * seconds late therefore still finds it held and skips, instead of running the
 * purge a second time. If Redis is unreachable the run is skipped; the next
 * night retries.
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TokenCleanupService {

  static final String LOCK_KEY = "lock:token-cleanup";
  static final Duration LOCK_TTL = Duration.ofMinutes(10);

  private final RefreshTokenRepository refreshTokenRepository;
  private final StringRedisTemplate stringRedisTemplate;

  @Scheduled(cron = "0 0 2 * * *")
  @Transactional
  public void cleanupExpiredTokens() {
    if (!acquireLock()) {
      return;
    }
    log.info("Starting cleanup of expired/revoked refresh tokens");
    refreshTokenRepository.deleteExpiredOrRevokedTokens(Instant.now());
    log.info("Cleanup of refresh tokens completed");
  }

  private boolean acquireLock() {
    try {
      String owner = ManagementFactory.getRuntimeMXBean().getName();
      Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(LOCK_KEY, owner, LOCK_TTL);
      if (!Boolean.TRUE.equals(acquired)) {
        log.debug("Token cleanup skipped: another instance holds {}", LOCK_KEY);
        return false;
      }
      return true;
    } catch (RuntimeException ex) {
      log.warn("Token cleanup skipped: could not acquire Redis lock {} ({})", LOCK_KEY, ex.getMessage());
      return false;
    }
  }
}
