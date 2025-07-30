package com.spring.app.modules.user.services.impl;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import com.spring.app.common.response.BaseResponse;
import com.spring.app.exceptions.ResourceNotFoundException;
import com.spring.app.modules.auth.mapper.UserMapper;
import com.spring.app.modules.auth.repositories.UserRepository;
import com.spring.app.modules.user.dto.requests.UpdateUserDto;
import com.spring.app.modules.user.mapper.UpdateUserMapper;
import com.spring.app.modules.user.services.UserServiceInterface;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService implements UserServiceInterface {

  private final UserRepository userRepository;
  private final UserMapper userMapper;
  private final UpdateUserMapper updateUserMapper;

  @Override
  public ResponseEntity<?> getUserProfile(UUID userId) {
    var user = userRepository.findById(userId)
        .orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + userId));

    var response = userMapper.userToUserResponseDto(user);

    return ResponseEntity.ok().body(BaseResponse.success("Get user profile successfully", response));
  }

  @Override
  public ResponseEntity<?> updateUserProfile(UUID userId, UpdateUserDto updateUserDto) {
    var user = userRepository.findById(userId)
        .orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + userId));

    updateUserMapper.updateUserDtoToUser(updateUserDto, user);

    var userRes = userRepository.save(user);

    var response = userMapper.userToUserResponseDto(userRes);

    return ResponseEntity.ok().body(BaseResponse.success("Update user profile successfully", response));
  }

}
