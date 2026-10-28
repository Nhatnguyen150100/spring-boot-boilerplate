package com.spring.app.integration;

import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

/**
 * Base class for the opt-in integration tests ({@code mvn -Pintegration verify}).
 *
 * <p>
 * Real MySQL (schema built by Flyway, checked by {@code ddl-auto=validate})
 * and real Redis, started once per JVM and shared by every {@code *IT} class
 * so the Spring context is cached across them. Ryuk removes the containers
 * when the JVM exits. Everything else comes from the {@code test} profile.
 * SMTP is replaced by a mock: these tests are about persistence, Redis and
 * the HTTP flow, not mail delivery.
 * </p>
 *
 * <p>
 * Subclasses must not add their own {@code @MockitoBean}s or properties:
 * any difference in configuration starts a second Spring context.
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

  static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
      .withDatabaseName("db_test_spring")
      .withUsername("springapp")
      .withPassword("springapp")
      .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

  static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
      .withExposedPorts(6379);

  static {
    MYSQL.start();
    REDIS.start();
  }

  @MockitoBean
  protected JavaMailSender javaMailSender;

  @DynamicPropertySource
  static void containerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306)
        + "/db_test_spring?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8");
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");

    // Production schema path: Flyway migrates, Hibernate only validates.
    registry.add("spring.flyway.enabled", () -> "true");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");

    // Host and port only. Timeouts come from application.yml, as in production.
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    // Explicitly empty / off: a developer's .env REDIS_PASSWORD or
    // REDIS_SSL_ENABLED must not reach a passwordless plain-TCP container.
    registry.add("spring.data.redis.password", () -> "");
    registry.add("spring.data.redis.ssl.enabled", () -> "false");
    // The test profile turns the startup check off; here Redis is real.
    registry.add("application.redis.fail-fast", () -> "true");

    // Every request in these tests comes from 127.0.0.1; lift the per-IP
    // quotas so the suite never trips them no matter how many ITs share a JVM.
    registry.add("application.rate-limit.auth.requests-per-minute", () -> "1000");
    registry.add("application.rate-limit.auth.requests-per-hour", () -> "10000");
    registry.add("application.rate-limit.auth.requests-per-day", () -> "100000");
    registry.add("application.rate-limit.api.requests-per-minute", () -> "1000");
    registry.add("application.rate-limit.api.requests-per-hour", () -> "10000");
    registry.add("application.rate-limit.api.requests-per-day", () -> "100000");
  }

  @BeforeEach
  void stubMailSender() {
    when(javaMailSender.createMimeMessage()).thenAnswer(invocation -> new MimeMessage((Session) null));
  }
}
