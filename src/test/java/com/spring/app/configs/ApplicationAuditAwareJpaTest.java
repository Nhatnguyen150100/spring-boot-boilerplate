package com.spring.app.configs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.auth.repositories.UserRepository;

/**
 * Saves real entities through Hibernate with a security context in place, which
 * is exactly the path PUT /users/me/update takes.
 */
@DataJpaTest
@ActiveProfiles("test")
class ApplicationAuditAwareJpaTest {

  @TestConfiguration
  @EnableJpaAuditing(auditorAwareRef = "applicationAuditAware")
  static class AuditingConfig {

    @Bean
    ApplicationAuditAware applicationAuditAware() {
      return new ApplicationAuditAware();
    }
  }

  @Autowired
  private UserRepository userRepository;

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void insertAndUpdateAreAttributedToTheAuthenticatedUsersId() {
    User creator = actor("11111111-1111-1111-1111-111111111111", "creator@example.com");
    authenticateAs(creator);

    User saved = userRepository.saveAndFlush(
        User.builder().email("new@example.com").password("hash").build());

    assertThat(saved.getCreatedBy()).isEqualTo(creator.getId().toString());
    assertThat(saved.getUpdatedBy()).isEqualTo(creator.getId().toString());
    assertThat(saved.getCreatedAt()).isNotNull();

    User editor = actor("22222222-2222-2222-2222-222222222222", "editor@example.com");
    authenticateAs(editor);
    saved.setFullName("Renamed User");

    User updated = userRepository.saveAndFlush(saved);

    assertThat(updated.getCreatedBy()).isEqualTo(creator.getId().toString());
    assertThat(updated.getUpdatedBy()).isEqualTo(editor.getId().toString());
  }

  @Test
  void insertWithoutAuthenticationIsAttributedToSystem() {
    User saved = userRepository.saveAndFlush(
        User.builder().email("anonymous-signup@example.com").password("hash").build());

    assertThat(saved.getCreatedBy()).isEqualTo("SYSTEM");
  }

  private static User actor(String id, String email) {
    User user = User.builder().email(email).password("hash").build();
    user.setId(UUID.fromString(id));
    return user;
  }

  private static void authenticateAs(User user) {
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
  }
}
