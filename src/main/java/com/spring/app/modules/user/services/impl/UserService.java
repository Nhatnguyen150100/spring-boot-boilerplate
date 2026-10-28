package com.spring.app.modules.user.services.impl;

import java.util.UUID;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.spring.app.configs.CacheConfig;
import com.spring.app.exceptions.ResourceNotFoundException;
import com.spring.app.modules.auth.dto.response.UserResponseDto;
import com.spring.app.modules.auth.mapper.AuthMapper;
import com.spring.app.modules.auth.repositories.UserRepository;
import com.spring.app.modules.user.dto.requests.UpdateUserDto;
import com.spring.app.modules.user.mapper.UpdateUserMapper;
import com.spring.app.modules.user.services.UserServiceInterface;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Profile reads are cached as an immutable {@link UserResponseDto} (never the
 * entity, never a ResponseEntity) under {@link CacheConfig#USER_PROFILE},
 * keyed by user id. Any write evicts the entry.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserService implements UserServiceInterface {

  private final UserRepository userRepository;
  private final AuthMapper userMapper;
  private final UpdateUserMapper updateUserMapper;

  @Override
  @Transactional(readOnly = true)
  @Cacheable(value = CacheConfig.USER_PROFILE, key = "#userId")
  public UserResponseDto getUserProfile(UUID userId) {
    var user = userRepository.findById(userId)
        .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));

    log.debug("User profile loaded from the database for userId: {}", userId);
    return userMapper.userToUserResponseDto(user);
  }

  @Override
  @Transactional
  @CacheEvict(value = CacheConfig.USER_PROFILE, key = "#userId")
  public UserResponseDto updateUserProfile(UUID userId, UpdateUserDto updateUserDto) {
    var user = userRepository.findById(userId)
        .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));

    updateUserMapper.updateUserDtoToUser(updateUserDto, user);

    // Flush so the auditing fields in the response reflect this update.
    var saved = userRepository.saveAndFlush(user);

    log.info("User profile updated for userId: {}", userId);
    return userMapper.userToUserResponseDto(saved);
  }
}
