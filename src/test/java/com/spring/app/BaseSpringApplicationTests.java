package com.spring.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Smoke test: every bean wires up and every {@code @ConfigurationProperties}
 * class passes its {@code @Validated} constraints.
 *
 * <p>
 * Runs on the {@code test} profile (H2, Flyway off), so it needs nothing
 * running on the machine.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
class BaseSpringApplicationTests {

  @Test
  void contextLoads() {
  }

}
