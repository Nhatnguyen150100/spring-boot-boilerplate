package com.spring.app.modules.auth.dto.request;

import com.spring.app.common.validation.annotations.StrongPassword;
import com.spring.app.common.validation.annotations.ValidEmail;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record AuthRequestDto(

    @ValidEmail
    @Schema(description = "Email address of the user", example = "user1@gmail.com")
    String email,

    @NotBlank(message = "Password is required") 
    @StrongPassword 
    @Schema(description = "Password of the user", example = "StrongP@ss123")
    String password

) {
}