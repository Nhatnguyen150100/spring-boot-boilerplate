package com.spring.app.configs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.List;

import org.apache.catalina.valves.RemoteIpValve;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.spring.app.enums.ERole;
import com.spring.app.enums.EUserStatus;
import com.spring.app.exceptions.RateLimitExceededException;
import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.auth.repositories.UserRepository;
import com.spring.app.modules.auth.services.TokenRevocationService;
import com.spring.app.shared.interfaces.JwtServiceInterface;
import com.spring.app.shared.interfaces.RedisServiceInterface;
import com.spring.app.shared.services.RateLimitManagerService;

/**
 * The real security chain, RateLimitFilter, exception resolver and actuator
 * on H2, booted with the prod forwarded-headers strategy on a real Tomcat
 * (RANDOM_PORT: Boot only adds its RemoteIpValve to an embedded server;
 * requests still go through MockMvc). Redis is never
 * contacted for rate limiting or tokens: RateLimitManagerService,
 * RedisServiceInterface and TokenRevocationService are mocks.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
    "server.forward-headers-strategy=native",
    "application.trusted-proxies=10.0.0.1,10.0.0.2",
    // no Redis in tests: keeps its failing health check (and stack trace) out of the output
    "management.health.redis.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebEdgeIntegrationTest {

  @Autowired
  private MockMvc mockMvc;
  @Autowired
  private UserRepository userRepository;
  @Autowired
  private PasswordEncoder passwordEncoder;
  @Autowired
  private JwtServiceInterface jwtService;
  @Autowired
  private ServletWebServerApplicationContext context;

  @MockitoBean
  private RateLimitManagerService rateLimitManagerService;
  @MockitoBean
  private RedisServiceInterface redisService;
  @MockitoBean
  private TokenRevocationService tokenRevocationService;

  @AfterEach
  void tearDown() {
    userRepository.deleteAll();
  }

  @Test
  void rejectedRequestIsA429WithTheStandardBody() throws Exception {
    doThrow(new RateLimitExceededException("Rate limit exceeded for API"))
        .when(rateLimitManagerService).checkRateLimitAndThrow(any(), anyString());

    mockMvc.perform(get("/api/v1/users/me"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().stringValues("Retry-After", "60")) // once: filter and handler both set it
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.statusCode").value(429))
        .andExpect(jsonPath("$.message").value("Rate limit exceeded for API"))
        .andExpect(jsonPath("$.timestamp").exists());
  }

  @Test
  void healthComponentsAreShownToAdminsOnly() throws Exception {
    User admin = userRepository.save(User.builder()
        .email("admin@example.com").password(passwordEncoder.encode("Str0ng!Pass")).fullName("Admin")
        .role(ERole.ADMIN).status(EUserStatus.ACTIVE).build());

    mockMvc.perform(get("/actuator/health"))
        .andExpect(jsonPath("$.status").exists())
        .andExpect(jsonPath("$.components").doesNotExist());
    mockMvc.perform(get("/actuator/health").header(AUTHORIZATION, "Bearer " + jwtService.generateToken(admin)))
        .andExpect(jsonPath("$.components.db").exists());
  }

  @Test
  void nativeForwardedHeadersTrustOnlyTheConfiguredProxies() {
    TomcatWebServer webServer = (TomcatWebServer) context.getWebServer();
    List<RemoteIpValve> valves = Arrays.stream(webServer.getTomcat().getEngine().getPipeline().getValves())
        .filter(RemoteIpValve.class::isInstance)
        .map(RemoteIpValve.class::cast)
        .toList();

    assertThat(valves).singleElement()
        .satisfies(valve -> assertThat(valve.getInternalProxies()).isEqualTo("\\Q10.0.0.1\\E|\\Q10.0.0.2\\E"));
  }
}
