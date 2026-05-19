package com.spring.app.modules.auth.dto.response;

import lombok.Builder;

@Builder
public record TokenResponseDto(
  String accessToken,
  String refreshToken
) {}
