package com.spring.app.modules.auth.dto.request;

import jakarta.validation.constraints.NotEmpty;
import lombok.Builder;

@Builder
public record RefreshTokenDto(
  @NotEmpty(message = "Refresh token is required")
  String refreshToken
) {}
