package com.spring.app.modules.user.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.user.dto.requests.UpdateUserDto;
import com.spring.app.modules.user.services.UserServiceInterface;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/users")
@Tag(name = "User Management", description = "APIs for managing user accounts")
@RequiredArgsConstructor
@Slf4j
public class UserController {
  private final UserServiceInterface userService;

  @Operation(summary = "Get user profile", description = "Retrieve the profile of a user by their ID")
  @GetMapping("/me")
  public ResponseEntity<?> getUserProfile() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    var user = (User) auth.getPrincipal();

    return userService.getUserProfile(user.getId());
  }

  @Operation(summary = "Update user profile", description = "Update the profile of the authenticated user")
  @PutMapping("/me/update")
  public ResponseEntity<?> updateUserProfile(@Valid @RequestBody UpdateUserDto updateUserDto) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    var user = (User) auth.getPrincipal();

    return userService.updateUserProfile(user.getId(), updateUserDto);
  }
}
