package com.spring.app.modules.auth.dto.request;

import com.spring.app.common.validation.annotations.MaxUtf8Bytes;
import com.spring.app.common.validation.annotations.ValidEmail;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * No {@code @StrongPassword} here on purpose: the policy applies when a
 * password is set, not when it is used, so tightening it later cannot lock
 * existing users out. The byte cap matches BCrypt's 72-byte input limit.
 */
public record LoginRequestDto(
    @ValidEmail
    @Schema(description = "Email address of the user", example = "user1@gmail.com")
    String email,

    @NotBlank(message = "Password is required")
    @MaxUtf8Bytes(value = 72, message = "Password must be at most 72 bytes")
    @Schema(description = "Password of the user", example = "StrongP@ss123")
    String password

) {
}
