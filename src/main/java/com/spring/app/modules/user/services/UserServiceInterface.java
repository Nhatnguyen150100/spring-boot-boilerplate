package com.spring.app.modules.user.services;

import com.spring.app.modules.auth.dto.response.UserResponseDto;
import com.spring.app.modules.user.dto.requests.UpdateUserDto;

public interface UserServiceInterface {
  UserResponseDto getCurrentUserProfile();

  UserResponseDto updateCurrentUserProfile(UpdateUserDto updateUserDto);
}
