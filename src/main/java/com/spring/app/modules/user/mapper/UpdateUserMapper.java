package com.spring.app.modules.user.mapper;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.user.dto.requests.UpdateUserDto;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface UpdateUserMapper {
  /**
   * Maps the UpdateUserDto to an entity that can be used for updating the user
   * profile.
   *
   * @param updateUserDto the DTO containing the updated user information
   * @return an entity with the updated user information
   */
  @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
  @Mapping(target = "password", ignore = true) // Password should not be updated through this DTO
  @Mapping(target = "email", ignore = true) // Email should not be updated through this DTO
  @Mapping(target = "status", ignore = true) // Status should not be updated through this DTO
  @Mapping(target = "role", ignore = true) // Role should not be updated through this DTO
  @Mapping(target = "createdAt", ignore = true) // Ignore createdAt
  @Mapping(target = "createdBy", ignore = true) // Ignore createdBy
  @Mapping(target = "updatedAt", ignore = true) // Ignore updatedAt
  @Mapping(target = "updatedBy", ignore = true) // Ignore updatedBy
  @Mapping(target = "id", ignore = true) // Ignore id
  @Mapping(target = "authorities", ignore = true) // Ignore authorities
  void updateUserDtoToUser(UpdateUserDto updateUserDto, @MappingTarget User user);
}
