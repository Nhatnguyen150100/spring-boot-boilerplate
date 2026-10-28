package com.spring.app.modules.user.services;

import java.util.UUID;

import com.spring.app.modules.auth.dto.response.UserResponseDto;
import com.spring.app.modules.user.dto.requests.UpdateUserDto;

public interface UserServiceInterface {
  UserResponseDto getUserProfile(UUID userId);

  UserResponseDto updateUserProfile(UUID userId, UpdateUserDto updateUserDto);
}
