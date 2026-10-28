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

  /** The column holds SHA-256 hex digests of refresh JWTs (AuthServiceImpl), never the JWTs. */
  private static final String HASH = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";
  private static final String OTHER_HASH = "60303ae22b998861bce3b28f33eec1be758a213c86c93c076dbe9f558c11c752";

  @Test
  void revokeIfActiveLetsExactlyOneCallerWin() {
    User user = saveUser("u@example.com", EUserStatus.ACTIVE);
    refreshTokenRepository.save(RefreshToken.builder()
        .token(HASH).user(user).expiryDate(Instant.now().plusSeconds(60)).build());

    assertThat(refreshTokenRepository.revokeIfActive(HASH)).isEqualTo(1);
    assertThat(refreshTokenRepository.revokeIfActive(HASH)).isZero();
    assertThat(refreshTokenRepository.revokeIfActive(OTHER_HASH)).isZero();
    assertThat(refreshTokenRepository.findByToken(HASH)).get().extracting(RefreshToken::isRevoked).isEqualTo(true);
  }

  @Test
  void existsByEmailSeesDeletedAccounts() {
    saveUser("gone@example.com", EUserStatus.DELETED);

    assertThat(userRepository.existsByEmail("gone@example.com")).isTrue();
    assertThat(userRepository.existsByEmail("nobody@example.com")).isFalse();
  }
}
