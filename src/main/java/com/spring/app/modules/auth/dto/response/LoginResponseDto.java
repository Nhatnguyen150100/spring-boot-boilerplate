package com.spring.app.modules.auth.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Builder;

@Builder
public record LoginResponseDto(
  String accessToken,
  String refreshToken,
  
  @JsonProperty("user")
  UserResponseDto userResponseDto
) {}
