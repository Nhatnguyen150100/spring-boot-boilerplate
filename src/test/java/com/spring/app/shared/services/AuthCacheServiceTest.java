package com.spring.app.shared.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import com.spring.app.configs.CacheConfig;
import com.spring.app.enums.EUserStatus;
import com.spring.app.exceptions.ResourceNotFoundException;
import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.auth.repositories.UserRepository;

@ExtendWith(MockitoExtension.class)
class AuthCacheServiceTest {

  @Mock
  private UserRepository userRepository;

  private final CacheManager cacheManager = new ConcurrentMapCacheManager(CacheConfig.USER_PROFILE);

  private AuthCacheService service;
  private User user;

  @BeforeEach
  void setUp() {
    service = new AuthCacheService(userRepository, cacheManager);
    user = User.builder()
        .email("john@example.com")
        .password("$2a$10$hashhashhash")
        .status(EUserStatus.PENDING)
        .build();
    user.setId(UUID.fromString("5d1c8a0b-2f4e-4c3a-8b6d-9e7f1a2b3c4d"));
  }

  @Test
  void getUserByEmailNormalisesTheAddressAndReturnsTheManagedEntity() {
    when(userRepository.findByEmail("john@example.com")).thenReturn(Optional.of(user));

    User found = service.getUserByEmail("  John@Example.COM ");

    assertThat(found).isSameAs(user);
    assertThat(found.getPassword()).isEqualTo("$2a$10$hashhashhash");
  }

  @Test
  void getUserByEmailDoesNotEchoTheAddressWhenMissing() {
    when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getUserByEmail("ghost@example.com"))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessage("User not found");
  }

  @Test
  void updateCachedUserEvictsTheUsersProfileDto() {
    profileCache().put(user.getId(), "stale profile");

    assertThat(service.updateCachedUser(user)).isSameAs(user);
    assertThat(profileCache().get(user.getId())).isNull();
  }

  @Test
  void evictCachedUserResolvesTheIdAndEvictsTheProfileDto() {
    when(userRepository.findByEmail("john@example.com")).thenReturn(Optional.of(user));
    profileCache().put(user.getId(), "stale profile");

    service.evictCachedUser("John@Example.com");

    assertThat(profileCache().get(user.getId())).isNull();
  }

  @Test
  void evictionSwallowsCacheFailuresSoACommittedChangeIsNotReportedAsAnError() {
    Cache failing = mock(Cache.class);
    doThrow(new IllegalStateException("redis down")).when(failing).evict(user.getId());
    CacheManager failingManager = mock(CacheManager.class);
    when(failingManager.getCache(CacheConfig.USER_PROFILE)).thenReturn(failing);
    AuthCacheService failingService = new AuthCacheService(userRepository, failingManager);

    assertThat(failingService.updateCachedUser(user)).isSameAs(user);
    verify(failing).evict(user.getId());
  }

  @Test
  void normalizeEmailTrimsAndLowerCases() {
    assertThat(AuthCacheService.normalizeEmail("  Mixed.Case@Example.ORG ")).isEqualTo("mixed.case@example.org");
    assertThat(AuthCacheService.normalizeEmail(null)).isNull();
  }

  private Cache profileCache() {
    return Objects.requireNonNull(cacheManager.getCache(CacheConfig.USER_PROFILE));
  }
}
