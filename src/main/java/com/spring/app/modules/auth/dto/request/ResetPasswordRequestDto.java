package com.spring.app.modules.auth.dto.request;

import com.spring.app.common.validation.annotations.StrongPassword;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;

@Builder
public record ResetPasswordRequestDto(
  @NotBlank(message = "Email is required")
  @Email(message = "Email format is not valid")
  @Size(max = 255, message = "Email must be less than 255 characters")
  @Schema(description = "Registered email address", example = "user@example.com")
  String email,

  @NotBlank(message = "OTP is required")
  @Schema(description = "OTP received via email", example = "123456")
  String otp,

  @NotBlank(message = "New password is required")
  @StrongPassword
  @Schema(description = "New password", example = "NewStrongP@ss123")
  String newPassword
) {}
