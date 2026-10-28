package com.spring.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;

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

  /**
   * logback-spring.xml must not attach file appenders under the test profile:
   * they would create ./logs in the working tree on every {@code mvn test}.
   */
  @Test
  void testProfileLogsToConsoleOnly() {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    List<String> appenders = new ArrayList<>();
    root.iteratorForAppenders().forEachRemaining(appender -> appenders.add(appender.getName()));

    assertThat(appenders).containsExactly("CONSOLE");
  }
}
