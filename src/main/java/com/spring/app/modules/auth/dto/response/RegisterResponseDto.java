package com.spring.app.modules.auth.dto.response;

import lombok.Builder;

@Builder
public record RegisterResponseDto(
  String email
) {}
