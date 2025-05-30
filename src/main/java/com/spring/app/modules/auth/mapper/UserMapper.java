package com.spring.app.modules.auth.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

import com.spring.app.modules.auth.dto.request.RegisterRequestDto;
import com.spring.app.modules.auth.dto.response.UserResponseDto;
import com.spring.app.modules.auth.entities.User;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface UserMapper {
  @Mapping(target = "avatarUrl", ignore = true)
  @Mapping(target = "phone", ignore = true)
  @Mapping(target = "locked", ignore = true)
  @Mapping(target = "role", ignore = true)
  User registerRequestDtoToUser(RegisterRequestDto dto);

  UserResponseDto userToUserResponseDto(User user);
}
