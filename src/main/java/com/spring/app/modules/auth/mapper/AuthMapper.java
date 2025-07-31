package com.spring.app.modules.auth.mapper;

import java.time.Instant;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

import com.spring.app.modules.auth.dto.response.LoginResponseDto;
import com.spring.app.modules.auth.dto.response.RegisterResponseDto;
import com.spring.app.modules.auth.dto.response.TokenResponseDto;
import com.spring.app.modules.auth.dto.response.UserResponseDto;
import com.spring.app.modules.auth.entities.RefreshToken;
import com.spring.app.modules.auth.entities.User;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface AuthMapper {

  UserResponseDto userToUserResponseDto(User user);

  RegisterResponseDto userToRegisterResponseDto(User user);

  @Mapping(target = "userResponseDto", source = "user")
  LoginResponseDto userToLoginResponseDto(User user, String accessToken, String refreshToken);

  TokenResponseDto newTokenToTokenResponseDto(String accessToken, String refreshToken);

  RefreshToken userToRefreshToken(User user, String token, Instant expiryDate);
}