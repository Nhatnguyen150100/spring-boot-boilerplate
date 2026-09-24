package com.spring.app.modules.auth.dto.request;

import com.spring.app.common.validation.annotations.ValidEmail;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
public record ForgotPasswordRequestDto(
  @ValidEmail
  @Schema(description = "Registered email address", example = "user@example.com")
  String email
) {}
