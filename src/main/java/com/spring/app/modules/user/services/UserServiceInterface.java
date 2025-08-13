package com.spring.app.modules.user.services;

import java.util.UUID;

import org.springframework.http.ResponseEntity;

import com.spring.app.modules.user.dto.requests.UpdateUserDto;

public interface UserServiceInterface {
  ResponseEntity<?> getUserProfile(UUID userId);

  ResponseEntity<?> updateUserProfile(UUID userId, UpdateUserDto updateUserDto);
}
