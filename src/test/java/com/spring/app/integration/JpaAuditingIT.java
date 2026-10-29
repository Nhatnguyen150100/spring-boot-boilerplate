package com.spring.app.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.auth.repositories.UserRepository;

/** Auditing columns are populated on real MySQL inserts and updates. */
class JpaAuditingIT extends IntegrationTestBase {

  @Autowired
  private UserRepository userRepository;

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void anonymousInsertIsStampedWithSystemAuditorAndTimestamps() {
    User saved = userRepository.saveAndFlush(newUser());

    User reloaded = userRepository.findById(saved.getId()).orElseThrow();
    assertThat(reloaded.getCreatedAt()).isNotNull();
    assertThat(reloaded.getUpdatedAt()).isNotNull();
    assertThat(reloaded.getCreatedBy()).isEqualTo("SYSTEM");
    assertThat(reloaded.getUpdatedBy()).isEqualTo("SYSTEM");
  }

  @Test
  void insertIsStampedWithTheCreatorAndUpdateWithTheEditor() {
    User creator = userRepository.saveAndFlush(newUser());
    User editor = userRepository.saveAndFlush(newUser());
    String creatorId = creator.getId().toString();
    String editorId = editor.getId().toString();

    authenticateAs(creator);
    User target = userRepository.saveAndFlush(newUser());

    User inserted = userRepository.findById(target.getId()).orElseThrow();
    assertThat(inserted.getCreatedBy()).isEqualTo(creatorId);
    assertThat(inserted.getUpdatedBy()).isEqualTo(creatorId);

    authenticateAs(editor);
    inserted.setFullName("Renamed By Editor");
    userRepository.saveAndFlush(inserted);

    User updated = userRepository.findById(target.getId()).orElseThrow();
    // created_by is updatable = false: the editor must not overwrite it.
    assertThat(updated.getCreatedBy()).isEqualTo(creatorId);
    assertThat(updated.getUpdatedBy()).isEqualTo(editorId);
    assertThat(updated.getFullName()).isEqualTo("Renamed By Editor");
    assertThat(updated.getUpdatedAt()).isAfterOrEqualTo(updated.getCreatedAt());
  }

  private static void authenticateAs(User actor) {
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(actor, null, actor.getAuthorities()));
  }

  private static User newUser() {
    return User.builder()
        .email("audit-" + UUID.randomUUID() + "@example.com")
        .password("not-a-real-hash")
        .fullName("Audit Subject")
        .build();
  }
}
