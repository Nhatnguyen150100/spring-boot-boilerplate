package com.spring.app.shared.services;

import java.time.Instant;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.spring.app.modules.auth.repositories.RefreshTokenRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class TokenCleanupService {

  private final RefreshTokenRepository refreshTokenRepository;

  @Scheduled(cron = "0 0 2 * * *")
  @Transactional
  public void cleanupExpiredTokens() {
    log.info("Starting cleanup of expired/revoked refresh tokens");
    refreshTokenRepository.deleteExpiredOrRevokedTokens(Instant.now());
    log.info("Cleanup of refresh tokens completed");
  }
}
