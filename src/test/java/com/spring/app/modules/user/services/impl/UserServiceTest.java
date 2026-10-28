package com.spring.app.modules.user.services.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.Transactional;

import com.spring.app.configs.CacheConfig;
import com.spring.app.enums.EUserStatus;
import com.spring.app.exceptions.ResourceNotFoundException;
import com.spring.app.modules.auth.dto.response.UserResponseDto;
import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.auth.mapper.AuthMapperImpl;
import com.spring.app.modules.auth.repositories.UserRepository;
import com.spring.app.modules.user.dto.requests.UpdateUserDto;
import com.spring.app.modules.user.mapper.UpdateUserMapperImpl;
import com.spring.app.modules.user.services.UserServiceInterface;

@SpringJUnitConfig(UserServiceTest.Config.class)
class UserServiceTest {

  @Configuration
  @EnableCaching
  static class Config {

    @Bean
    CacheManager cacheManager() {
      return new ConcurrentMapCacheManager(CacheConfig.USER_PROFILE);
    }

    @Bean
    UserRepository userRepository() {
      return mock(UserRepository.class);
    }

    @Bean
    UserService userService(UserRepository userRepository) {
      return new UserService(userRepository, new AuthMapperImpl(), new UpdateUserMapperImpl());
    }
  }

  private static final UUID USER_ID = UUID.fromString("7f3c2a3e-9d7b-4b6a-9a51-2f1f0d6c8e11");

  @Autowired
  private UserServiceInterface userService;

  @Autowired
  private UserRepository userRepository;

  @Autowired
  private CacheManager cacheManager;

  private User user;

  @BeforeEach
  void setUp() {
    reset(userRepository);
    profileCache().clear();

    user = User.builder()
        .email("john@example.com")
        .password("hash")
        .fullName("John Doe")
        .dateOfBirth(LocalDate.of(1990, 1, 15))
        .status(EUserStatus.ACTIVE)
        .build();
    user.setId(USER_ID);
    user.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
    user.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
    when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
  }

  @Test
  void profileIsReturnedAsADtoAndServedFromTheCacheTheSecondTime() {
    UserResponseDto first = userService.getUserProfile(USER_ID);
    UserResponseDto second = userService.getUserProfile(USER_ID);

    assertThat(first.email()).isEqualTo("john@example.com");
    assertThat(first.status()).isEqualTo("ACTIVE");
    assertThat(second).isEqualTo(first);
    verify(userRepository, times(1)).findById(USER_ID);
    assertThat(profileCache().get(USER_ID)).isNotNull();
    assertThat(profileCache().get(USER_ID).get()).isInstanceOf(UserResponseDto.class);
  }

  @Test
  void updateEvictsTheCachedProfile() {
    when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
    userService.getUserProfile(USER_ID); // warm the cache

    UserResponseDto updated = userService.updateUserProfile(USER_ID,
        UpdateUserDto.builder().fullName("Jane Doe").build());

    assertThat(updated.fullName()).isEqualTo("Jane Doe");
    assertThat(profileCache().get(USER_ID)).isNull();
    assertThat(userService.getUserProfile(USER_ID).fullName()).isEqualTo("Jane Doe");
  }

  @Test
  void unknownUserIsNotFoundAndNothingIsCached() {
    UUID unknown = UUID.fromString("00000000-0000-0000-0000-000000000001");

    assertThatThrownBy(() -> userService.getUserProfile(unknown))
        .isInstanceOf(ResourceNotFoundException.class);
    assertThat(profileCache().get(unknown)).isNull();
  }

  @Test
  void transactionsAreSpringOnesAndTheReadIsReadOnly() throws NoSuchMethodException {
    Method read = UserService.class.getMethod("getUserProfile", UUID.class);
    Method write = UserService.class.getMethod("updateUserProfile", UUID.class, UpdateUserDto.class);

    Transactional readTx = AnnotationUtils.findAnnotation(read, Transactional.class);
    Transactional writeTx = AnnotationUtils.findAnnotation(write, Transactional.class);

    assertThat(readTx).isNotNull();
    assertThat(readTx.readOnly()).isTrue();
    assertThat(writeTx).isNotNull();
    assertThat(writeTx.readOnly()).isFalse();
    assertThat(read.isAnnotationPresent(jakarta.transaction.Transactional.class)).isFalse();
    assertThat(write.isAnnotationPresent(jakarta.transaction.Transactional.class)).isFalse();
  }

  private Cache profileCache() {
    return Objects.requireNonNull(cacheManager.getCache(CacheConfig.USER_PROFILE));
  }
}
