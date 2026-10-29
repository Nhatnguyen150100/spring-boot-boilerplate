package com.spring.app.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.app.enums.EUserStatus;
import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.auth.repositories.UserRepository;
import com.spring.app.utils.OtpFunctionUtil;

/**
 * register -> activate (OTP read back from real Redis) -> login -> cached
 * profile read -> refresh (rotation) -> logout, through the full filter chain
 * (rate limit, JWT, security) against real MySQL and Redis.
 */
class AuthRoundTripIT extends IntegrationTestBase {

  private static final String PASSWORD = "StrongP@ss123";
  private static final String FULL_NAME = "Integration Tester";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private OtpFunctionUtil otpFunctionUtil;

  @Autowired
  private StringRedisTemplate stringRedisTemplate;

  @Autowired
  private UserRepository userRepository;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Test
  void registerActivateLoginRefreshLogout() throws Exception {
    String email = "it-" + UUID.randomUUID() + "@example.com";

    // 1. Register: PENDING account, OTP generated on the e-mail executor.
    mockMvc.perform(post("/api/v1/auth/register").contentType(APPLICATION_JSON)
        .content(json(Map.of("email", email, "password", PASSWORD, "fullName", FULL_NAME))))
        .andExpect(status().isCreated());
    assertThat(userRepository.findByEmail(email)).get()
        .extracting(User::getStatus).isEqualTo(EUserStatus.PENDING);

    // 2. Activate with the OTP that landed in Redis, under a key that is a
    // hash of the address, never the address itself.
    String otp = awaitOtpFor(email);
    assertThat(stringRedisTemplate.hasKey(expectedOtpKey(email))).isTrue();
    assertThat(stringRedisTemplate.keys("*" + email + "*")).isEmpty();

    mockMvc.perform(post("/api/v1/auth/activate").contentType(APPLICATION_JSON)
        .content(json(Map.of("email", email, "otp", otp))))
        .andExpect(status().isOk());
    assertThat(otpFunctionUtil.retrieveOtp(email)).as("OTP is single-use").isNull();

    // 3. Login.
    JsonNode login = body(mockMvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
        .content(json(Map.of("email", email, "password", PASSWORD))))
        .andExpect(status().isOk()));
    String accessToken = login.at("/data/accessToken").asText();
    String refreshToken = login.at("/data/refreshToken").asText();
    assertThat(accessToken).isNotBlank();
    assertThat(refreshToken).isNotBlank();

    // 4. Profile twice through the real Redis cache. The first read stores the
    // DTO under the versioned key; the row is then changed behind the cache's
    // back, so the second read can only show the old name if it came from Redis
    // (and was deserialized back into the DTO).
    UUID userId = userRepository.findByEmail(email).orElseThrow().getId();
    String cacheKey = "v2:userProfile::" + userId;

    mockMvc.perform(get("/api/v1/users/me").header(AUTHORIZATION, "Bearer " + accessToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.email").value(email))
        .andExpect(jsonPath("$.data.fullName").value(FULL_NAME));
    assertThat(stringRedisTemplate.hasKey(cacheKey)).isTrue();
    assertThat(stringRedisTemplate.getExpire(cacheKey)).isPositive();

    jdbcTemplate.update("UPDATE users SET full_name = ? WHERE email = ?", "Changed Behind The Cache", email);

    mockMvc.perform(get("/api/v1/users/me").header(AUTHORIZATION, "Bearer " + accessToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.email").value(email))
        .andExpect(jsonPath("$.data.fullName").value(FULL_NAME));

    // 5. A refresh token is not an access token (token_type claim).
    mockMvc.perform(get("/api/v1/users/me").header(AUTHORIZATION, "Bearer " + refreshToken))
        .andExpect(status().isUnauthorized());

    // 6. Refresh: a new pair, and the presented refresh token is revoked (rotation).
    JsonNode refreshed = body(mockMvc.perform(post("/api/v1/auth/refresh-token").contentType(APPLICATION_JSON)
        .content(json(Map.of("refreshToken", refreshToken))))
        .andExpect(status().isOk()));
    String newAccessToken = refreshed.at("/data/accessToken").asText();
    String newRefreshToken = refreshed.at("/data/refreshToken").asText();
    assertThat(newAccessToken).isNotBlank().isNotEqualTo(accessToken);
    assertThat(newRefreshToken).isNotBlank().isNotEqualTo(refreshToken);

    mockMvc.perform(post("/api/v1/auth/refresh-token").contentType(APPLICATION_JSON)
        .content(json(Map.of("refreshToken", refreshToken))))
        .andExpect(status().isUnauthorized());

    mockMvc.perform(get("/api/v1/users/me").header(AUTHORIZATION, "Bearer " + newAccessToken))
        .andExpect(status().isOk());

    // 7. Logout needs the access token.
    mockMvc.perform(delete("/api/v1/auth/logout"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(delete("/api/v1/auth/logout").header(AUTHORIZATION, "Bearer " + newAccessToken))
        .andExpect(status().isOk());

    // 8. After logout the access token is blacklisted and the refresh token revoked.
    mockMvc.perform(get("/api/v1/users/me").header(AUTHORIZATION, "Bearer " + newAccessToken))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(post("/api/v1/auth/refresh-token").contentType(APPLICATION_JSON)
        .content(json(Map.of("refreshToken", newRefreshToken))))
        .andExpect(status().isUnauthorized());
  }

  private String json(Map<String, String> body) throws Exception {
    return objectMapper.writeValueAsString(body);
  }

  private JsonNode body(ResultActions result) throws Exception {
    return objectMapper.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  /** {@code otp:<hex SHA-256 of the lower-cased, trimmed e-mail>}, computed independently of OtpFunctionUtil. */
  private static String expectedOtpKey(String email) throws Exception {
    byte[] digest = MessageDigest.getInstance("SHA-256")
        .digest(email.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    return "otp:" + HexFormat.of().formatHex(digest);
  }

  /** Polls Redis for the OTP stored by the asynchronous e-mail task. */
  private String awaitOtpFor(String email) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      String otp = otpFunctionUtil.retrieveOtp(email);
      if (otp != null) {
        assertThat(otp).matches("\\d{" + OtpFunctionUtil.OTP_LENGTH + "}");
        return otp;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("No OTP for " + email + " appeared in Redis within 10s");
  }
}
