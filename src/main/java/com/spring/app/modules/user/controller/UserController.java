package com.spring.app.modules.user.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.spring.app.common.response.BaseResponse;
import com.spring.app.common.response.ResponseBuilder;
import com.spring.app.constants.ApplicationConstants;
import com.spring.app.modules.auth.dto.response.UserResponseDto;
import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.user.dto.requests.UpdateUserDto;
import com.spring.app.modules.user.services.UserServiceInterface;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping(ApplicationConstants.USER_BASE_PATH)
@Tag(name = "User Management", description = "APIs for managing user accounts")
@RequiredArgsConstructor
public class UserController {
  private final UserServiceInterface userService;

  @Operation(summary = "Get user profile", description = "Retrieve the profile of the authenticated user")
  @GetMapping("/me")
  public ResponseEntity<BaseResponse<UserResponseDto>> getUserProfile() {
    return ResponseBuilder.success("Get user profile successfully",
        userService.getUserProfile(currentUserId()));
  }

  @Operation(summary = "Update user profile", description = "Update the profile of the authenticated user")
  @PutMapping("/me/update")
  public ResponseEntity<BaseResponse<UserResponseDto>> updateUserProfile(
      @Valid @RequestBody UpdateUserDto updateUserDto) {
    return ResponseBuilder.success("Update user profile successfully",
        userService.updateUserProfile(currentUserId(), updateUserDto));
  }

  private UUID currentUserId() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    return ((User) auth.getPrincipal()).getId();
  }
}
