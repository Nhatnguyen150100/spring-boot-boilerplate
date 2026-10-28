package com.spring.app.configs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jayway.jsonpath.JsonPath;
import com.spring.app.configs.properties.JwtProperties;
import com.spring.app.enums.ERole;
import com.spring.app.enums.EUserStatus;
import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.auth.repositories.RefreshTokenRepository;
import com.spring.app.modules.auth.repositories.UserRepository;
import com.spring.app.modules.auth.services.LoginAttemptService;
import com.spring.app.shared.interfaces.JwtServiceInterface;
import com.spring.app.shared.interfaces.RedisServiceInterface;
import com.spring.app.shared.services.JwtService;

/**
 * The real SecurityConfig, JWT filter, rate-limit filter, exception handling
 * and actuator, on H2. Redis is never contacted: RedisServiceInterface (token
 * blacklist + rate-limit counters) and LoginAttemptService are mocks, so
 * `mvn test` needs nothing running. @AutoConfigureObservability keeps the
 * Prometheus registry that Spring Boot otherwise disables in tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "springdoc.api-docs.enabled=true",
    "management.endpoints.web.exposure.include=health,info,prometheus"
})
@Import(SecurityRulesIntegrationTest.ProbeController.class)
class SecurityRulesIntegrationTest {

  /** Test-only endpoints. Nested in a test class, so not component-scanned elsewhere. */
  @RestController
  @RequestMapping("/api/v1/test-probe")
  public static class ProbeController {

    @GetMapping("/me")
    public String me(Authentication authentication) {
      return authentication.getName();
    }

    @GetMapping("/admin")
    @PreAuthorize("hasRole('ADMIN')")
    public String admin() {
      return "admin-ok";
    }
  }

  private static final String PASSWORD = "Str0ng!Pass";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private RefreshTokenRepository refreshTokenRepository;
  @Autowired private JwtServiceInterface jwtService;
  @Autowired private JwtProperties jwtProperties;
  @Autowired private PasswordEncoder passwordEncoder;

  @MockitoBean private RedisServiceInterface redisService;
  @MockitoBean private LoginAttemptService loginAttemptService;

  private User activeUser;
  private User adminUser;
  private User inactiveUser;

  @BeforeEach
  void setUp() {
    activeUser = save("active@example.com", ERole.USER, EUserStatus.ACTIVE);
    adminUser = save("admin@example.com", ERole.ADMIN, EUserStatus.ACTIVE);
    inactiveUser = save("inactive@example.com", ERole.USER, EUserStatus.INACTIVE);
  }

  @AfterEach
  void tearDown() {
    refreshTokenRepository.deleteAll();
    userRepository.deleteAll();
  }

  private User save(String email, ERole role, EUserStatus status) {
    return userRepository.save(User.builder()
        .email(email).password(passwordEncoder.encode(PASSWORD)).fullName("Test User")
        .role(role).status(status).build());
  }

  private static String bearer(String token) {
    return "Bearer " + token;
  }

  // ---- JWT filter ----------------------------------------------------------------

  @Test
  void anAccessTokenAuthenticatesAProtectedRequest() throws Exception {
    mockMvc.perform(get("/api/v1/test-probe/me").header(AUTHORIZATION, bearer(jwtService.generateToken(activeUser))))
        .andExpect(status().isOk())
        .andExpect(content().string("active@example.com"));
  }

  @Test
  void noTokenIs401() throws Exception {
    mockMvc.perform(get("/api/v1/test-probe/me")).andExpect(status().isUnauthorized());
  }

  @Test
  void aRefreshTokenIsNotABearerToken() throws Exception {
    mockMvc.perform(get("/api/v1/test-probe/me")
            .header(AUTHORIZATION, bearer(jwtService.generateRefreshToken(activeUser))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void aGarbledTokenIs401NotAServerError() throws Exception {
    mockMvc.perform(get("/api/v1/test-probe/me").header(AUTHORIZATION, bearer("not-a-jwt")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void anExpiredTokenIs401() throws Exception {
    JwtProperties expired = new JwtProperties();
    expired.setSecretKey(jwtProperties.getSecretKey());
    expired.setExpiration(-60_000);
    String token = new JwtService(expired).generateToken(activeUser);

    mockMvc.perform(get("/api/v1/test-probe/me").header(AUTHORIZATION, bearer(token)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void aBlacklistedTokenIs401() throws Exception {
    String token = jwtService.generateToken(activeUser);
    when(redisService.isTokenBlacklisted(token)).thenReturn(true);

    mockMvc.perform(get("/api/v1/test-probe/me").header(AUTHORIZATION, bearer(token)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void aTokenOfANonActiveAccountIs401() throws Exception {
    mockMvc.perform(get("/api/v1/test-probe/me").header(AUTHORIZATION, bearer(jwtService.generateToken(inactiveUser))))
        .andExpect(status().isUnauthorized());
  }

  // ---- whitelist -------------------------------------------------------------------

  @Test
  void logoutNeedsAValidAccessToken() throws Exception {
    mockMvc.perform(delete("/api/v1/auth/logout")).andExpect(status().isUnauthorized());
    mockMvc.perform(delete("/api/v1/auth/logout").header(AUTHORIZATION, bearer("not-a-jwt")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void logoutWithAValidTokenBlacklistsIt() throws Exception {
    String token = jwtService.generateToken(activeUser);

    mockMvc.perform(delete("/api/v1/auth/logout").header(AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk());
    verify(redisService).blacklistToken(eq(token), anyLong());
  }

  @Test
  void publicAuthEndpointsNeedNoToken() throws Exception {
    // Reaches the controller: bean validation answers 400, not the entry point's 401.
    mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void onlyTheListedMethodIsPublic() throws Exception {
    mockMvc.perform(get("/api/v1/auth/login")).andExpect(status().isUnauthorized());
  }

  @Test
  void loginEndToEndIssuesAWorkingAccessToken() throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"Active@Example.com\",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
    String accessToken = JsonPath.read(result.getResponse().getContentAsString(), "$.data.accessToken");

    verify(loginAttemptService).reset("active@example.com", "127.0.0.1");
    mockMvc.perform(get("/api/v1/test-probe/me").header(AUTHORIZATION, bearer(accessToken)))
        .andExpect(status().isOk());
  }

  @Test
  void swaggerApiDocsArePublic() throws Exception {
    mockMvc.perform(get("/api-docs")).andExpect(status().isOk());
  }

  @Test
  void actuatorInfoAndHealthArePublic() throws Exception {
    mockMvc.perform(get("/actuator/info")).andExpect(status().isOk());
    int health = mockMvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus();
    assertThat(health).as("health may be 200 or 503 without Redis, but never 401/403").isNotIn(401, 403);
  }

  // ---- RBAC ------------------------------------------------------------------------

  @Test
  void prometheusIsAdminOnly() throws Exception {
    mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/actuator/prometheus").header(AUTHORIZATION, bearer(jwtService.generateToken(activeUser))))
        .andExpect(status().isForbidden());
    mockMvc.perform(get("/actuator/prometheus").header(AUTHORIZATION, bearer(jwtService.generateToken(adminUser))))
        .andExpect(status().isOk());
  }

  @Test
  void methodSecurityIsEnforced() throws Exception {
    mockMvc.perform(get("/api/v1/test-probe/admin").header(AUTHORIZATION, bearer(jwtService.generateToken(activeUser))))
        .andExpect(status().isForbidden());
    mockMvc.perform(get("/api/v1/test-probe/admin").header(AUTHORIZATION, bearer(jwtService.generateToken(adminUser))))
        .andExpect(status().isOk())
        .andExpect(content().string("admin-ok"));
  }
}
