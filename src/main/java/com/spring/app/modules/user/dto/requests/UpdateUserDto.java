package com.spring.app.modules.user.dto.requests;

import java.time.LocalDate;

import org.hibernate.validator.constraints.URL;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.spring.app.common.validation.annotations.ValidFullName;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;

@Builder
@Schema(description = "Data Transfer Object for updating user profile information")
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdateUserDto(
  @ValidFullName
  @Schema(description = "Full name of user", example = "Nguyễn Văn A")
  String fullName,

  @Size(max = 15, message = "Phone number must be less than 15 characters")
  @Pattern(regexp = "^[+]?[0-9\\s\\-()]+$", message = "Invalid phone number format")
  @Schema(description = "Phone number", example = "0987654321")
  String phone,

  @Size(max = 255, message = "Avatar URL must be less than 255 characters")
  @URL(regexp = "^https?://.+", message = "Avatar URL must be a valid http(s) URL")
  @Schema(description = "Avatar URL", example = "https://example.com/avatar.jpg")
  String avatarUrl,

  @Size(max = 255, message = "Address must be less than 255 characters")
  @Schema(description = "Address", example = "123 Main St, City, Country")
  String address,

  @Past(message = "Date of birth must be in the past")
  @JsonFormat(pattern = "yyyy-MM-dd")
  @Schema(description = "Date of birth (yyyy-MM-dd)", example = "1990-01-15")
  LocalDate dateOfBirth,

  @Size(max = 512, message = "Description must be less than 512 characters")
  @Schema(description = "Description", example = "I'm a software engineer")
  String description
) {}
