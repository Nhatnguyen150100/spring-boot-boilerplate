package com.spring.app.modules.auth.dto.request;

import com.spring.app.common.validation.annotations.MaxUtf8Bytes;
import com.spring.app.common.validation.annotations.StrongPassword;
import com.spring.app.common.validation.annotations.ValidEmail;
import com.spring.app.common.validation.annotations.ValidFullName;
import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequestDto(

    @ValidEmail
    @Schema(description = "Email address of the user", example = "user1@gmail.com")
    String email,

    @NotBlank(message = "Password is required")
    @StrongPassword
    @MaxUtf8Bytes(value = 72, message = "Password must be at most 72 bytes")
    @Schema(description = "Password of the user", example = "StrongP@ss123")
    String password,

    @NotBlank(message = "Full name is required")
    @ValidFullName
    @Schema(description = "Full name of the user", example = "Nguyễn Văn A")
    String fullName,

    @Size(max = 15, message = "Phone number must be less than 15 characters")
    @Pattern(regexp = "^[+]?[0-9\\s\\-()]+$", message = "Invalid phone number format")
    @Schema(description = "Phone number of the user", example = "+1234567890")
    String phone,

    @Size(max = 255, message = "Address must be less than 255 characters")
    @Schema(description = "Address of the user", example = "123 Main St, City, Country")
    String address

) {
}
