package com.spring.app.modules.auth.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.test.context.ActiveProfiles;

import com.spring.app.enums.EUserStatus;
import com.spring.app.modules.auth.entities.RefreshToken;
import com.spring.app.modules.auth.entities.User;

/**
 * JPA slice on H2 (test profile, Flyway off). ApplicationConfig is not part of
 * the slice, so the nested config switches auditing on for the NOT NULL
 * created_at / updated_at columns.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(RefreshTokenRepositoryTest.AuditingConfig.class)
class RefreshTokenRepositoryTest {

  @TestConfiguration
  @EnableJpaAuditing
  static class AuditingConfig {
  }

  @Autowired
  private UserRepository userRepository;

  @Autowired
  private RefreshTokenRepository refreshTokenRepository;

  private User saveUser(String email, EUserStatus status) {
    return userRepository.save(User.builder().email(email).password("x").status(status).build());
  }

  @Test
  void revokeIfActiveLetsExactlyOneCallerWin() {
    User user = saveUser("u@example.com", EUserStatus.ACTIVE);
    refreshTokenRepository.save(RefreshToken.builder()
        .token("tok-1").user(user).expiryDate(Instant.now().plusSeconds(60)).build());

    assertThat(refreshTokenRepository.revokeIfActive("tok-1")).isEqualTo(1);
    assertThat(refreshTokenRepository.revokeIfActive("tok-1")).isZero();
    assertThat(refreshTokenRepository.revokeIfActive("unknown")).isZero();
    assertThat(refreshTokenRepository.findByToken("tok-1")).get().extracting(RefreshToken::isRevoked).isEqualTo(true);
  }

  @Test
  void existsByEmailSeesDeletedAccounts() {
    saveUser("gone@example.com", EUserStatus.DELETED);

    assertThat(userRepository.existsByEmail("gone@example.com")).isTrue();
    assertThat(userRepository.existsByEmail("nobody@example.com")).isFalse();
  }
}
