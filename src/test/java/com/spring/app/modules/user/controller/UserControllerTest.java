package com.spring.app.modules.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.spring.app.modules.auth.dto.response.UserResponseDto;
import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.user.dto.requests.UpdateUserDto;
import com.spring.app.modules.user.services.UserServiceInterface;

class UserControllerTest {

  private static final UUID USER_ID = UUID.fromString("9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d");

  private final UserServiceInterface userService = mock(UserServiceInterface.class);
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.standaloneSetup(new UserController(userService)).build();

    User principal = User.builder().email("john@example.com").password("hash").build();
    principal.setId(USER_ID);
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
  }

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void getProfileWrapsTheServiceDtoInTheStandardResponse() throws Exception {
    when(userService.getUserProfile(USER_ID)).thenReturn(UserResponseDto.builder()
        .id(USER_ID)
        .email("john@example.com")
        .dateOfBirth(LocalDate.of(1990, 1, 15))
        .build());

    mockMvc.perform(get("/api/v1/users/me"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.statusCode").value(200))
        .andExpect(jsonPath("$.message").value("Get user profile successfully"))
        .andExpect(jsonPath("$.data.email").value("john@example.com"))
        .andExpect(jsonPath("$.data.dateOfBirth").value("1990-01-15"));
  }

  @Test
  void updateProfilePassesTheCallersIdAndBody() throws Exception {
    when(userService.updateUserProfile(eq(USER_ID), any(UpdateUserDto.class)))
        .thenReturn(UserResponseDto.builder().id(USER_ID).fullName("Jane Doe").build());

    mockMvc.perform(put("/api/v1/users/me/update")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"fullName\":\"Jane Doe\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.message").value("Update user profile successfully"))
        .andExpect(jsonPath("$.data.fullName").value("Jane Doe"));

    verify(userService).updateUserProfile(eq(USER_ID), argThat(dto -> "Jane Doe".equals(dto.fullName())));
  }
}
