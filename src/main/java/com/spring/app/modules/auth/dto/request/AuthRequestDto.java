package com.spring.app.modules.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.security.auth.message.callback.PasswordValidationCallback;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AuthRequestDto {
  @Email(message = "Email is not valid", regexp = "^[a-zA-Z0-9_!#$%&'*+/=?`{|}~^.-]+@[a-zA-Z0-9.-]+$")
  @Size(max = 255, message = "Email must be less than 255 characters")
  @NotBlank(message = "Email is required")
  @Schema(description = "Email address of the user", example = "user1@gmail.com")
  @NotEmpty(message = "Email cannot be empty")
  private String email;

  @NotBlank(message = "Password is required")
  @Size(min = 6, message = "Password must be at least 6 characters", groups = PasswordValidationCallback.class)
  @Size(max = 255, message = "Password must be less than 255 characters")
  @Schema(description = "Password of the user", example = "password")
  private String password;
}
