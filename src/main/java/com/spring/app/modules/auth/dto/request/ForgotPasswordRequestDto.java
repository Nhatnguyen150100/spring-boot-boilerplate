package com.spring.app.modules.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;

@Builder
public record ForgotPasswordRequestDto(
  @NotBlank(message = "Email is required")
  @Email(message = "Email format is not valid")
  @Size(max = 255, message = "Email must be less than 255 characters")
  @Schema(description = "Registered email address", example = "user@example.com")
  String email
) {}
