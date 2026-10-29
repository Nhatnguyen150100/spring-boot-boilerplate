package com.spring.app.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import com.spring.app.enums.EUserStatus;
import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.auth.repositories.UserRepository;
import com.spring.app.modules.user.dto.requests.UpdateUserDto;
import com.spring.app.modules.user.services.UserServiceInterface;
import com.spring.app.shared.interfaces.JwtServiceInterface;

/**
 * The userProfile cache on real Redis: reads populate
 * {@code v2:userProfile::<id>}, and a profile update evicts it only once the
 * update's transaction has committed.
 */
class UserProfileCacheIT extends IntegrationTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository userRepository;

  @Autowired
  private JwtServiceInterface jwtService;

  @Autowired
  private UserServiceInterface userService;

  @Autowired
  private StringRedisTemplate stringRedisTemplate;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Test
  void profileUpdateOverHttpEvictsTheCachedProfile() throws Exception {
    User user = activeUser();
    String bearer = "Bearer " + jwtService.generateToken(user);
    String cacheKey = cacheKey(user);

    mockMvc.perform(get("/api/v1/users/me").header(AUTHORIZATION, bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.fullName").value("Cache Subject"));
    assertThat(stringRedisTemplate.opsForValue().get(cacheKey)).contains("Cache Subject");

    mockMvc.perform(put("/api/v1/users/me/update").header(AUTHORIZATION, bearer).contentType(APPLICATION_JSON)
        .content("{\"fullName\":\"Updated Subject\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.fullName").value("Updated Subject"));
    assertThat(stringRedisTemplate.hasKey(cacheKey)).isFalse();

    mockMvc.perform(get("/api/v1/users/me").header(AUTHORIZATION, bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.fullName").value("Updated Subject"));
    assertThat(stringRedisTemplate.opsForValue().get(cacheKey)).contains("Updated Subject");
  }

  @Test
  void evictionIsDeferredUntilTheUpdateCommits() {
    User user = activeUser();
    String cacheKey = cacheKey(user);
    userService.getUserProfile(user.getId());
    assertThat(stringRedisTemplate.hasKey(cacheKey)).isTrue();

    transactionTemplate.executeWithoutResult(status -> {
      userService.updateUserProfile(user.getId(), UpdateUserDto.builder().fullName("Inside Transaction").build());
      // Not yet committed: evicting now would let a concurrent reader re-cache the old row.
      assertThat(stringRedisTemplate.hasKey(cacheKey)).as("evicted before commit").isTrue();
    });

    assertThat(stringRedisTemplate.hasKey(cacheKey)).as("evicted after commit").isFalse();
    assertThat(userService.getUserProfile(user.getId()).fullName()).isEqualTo("Inside Transaction");
  }

  private User activeUser() {
    return userRepository.saveAndFlush(User.builder()
        .email("cache-" + UUID.randomUUID() + "@example.com")
        .password("not-a-real-hash")
        .fullName("Cache Subject")
        .status(EUserStatus.ACTIVE)
        .build());
  }

  private static String cacheKey(User user) {
    return "v2:userProfile::" + user.getId();
  }
}
