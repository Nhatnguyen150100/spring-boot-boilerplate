package com.spring.app.modules.user.services.impl;

import java.util.UUID;

import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import com.spring.app.common.response.ResponseBuilder;
import com.spring.app.configs.CacheConfig;
import com.spring.app.exceptions.ResourceNotFoundException;
import com.spring.app.modules.auth.mapper.AuthMapper;
import com.spring.app.modules.auth.repositories.UserRepository;
import com.spring.app.modules.user.dto.requests.UpdateUserDto;
import com.spring.app.modules.user.mapper.UpdateUserMapper;
import com.spring.app.modules.user.services.UserServiceInterface;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService implements UserServiceInterface {

  private final UserRepository userRepository;
  private final AuthMapper userMapper;
  private final UpdateUserMapper updateUserMapper;

  @Override
  @Cacheable(value = CacheConfig.USER_PROFILE, key = "#userId")
  public ResponseEntity<?> getUserProfile(UUID userId) {
    var user = userRepository.findById(userId)
        .orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + userId));

    var response = userMapper.userToUserResponseDto(user);

    log.info("User profile retrieved successfully for userId: {}", userId);
    return ResponseBuilder.success("Get user profile successfully", response);
  }

  @Override
  @Transactional
  @CachePut(value = CacheConfig.USER_PROFILE, key = "#userId")
  public ResponseEntity<?> updateUserProfile(UUID userId, UpdateUserDto updateUserDto) {
    var user = userRepository.findById(userId)
        .orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + userId));

    updateUserMapper.updateUserDtoToUser(updateUserDto, user);

    var userRes = userRepository.save(user);

    var response = userMapper.userToUserResponseDto(userRes);

    log.info("User profile updated successfully for userId: {}", userId);
    return ResponseBuilder.success("Update user profile successfully", response);
  }

}
