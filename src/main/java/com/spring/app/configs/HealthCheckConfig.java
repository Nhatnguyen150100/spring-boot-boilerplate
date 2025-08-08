package com.spring.app.configs;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class HealthCheckConfig {

  @Bean
  HealthIndicator databaseHealthIndicator(JdbcTemplate jdbcTemplate) {
    return () -> {
      try {
        jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        return Health.up()
            .withDetail("database", "MySQL")
            .withDetail("status", "Connected")
            .build();
      } catch (Exception e) {
        return Health.down()
            .withDetail("database", "MySQL")
            .withDetail("error", e.getMessage())
            .build();
      }
    };
  }

  @Bean
  HealthIndicator redisHealthIndicator() {
    return () -> {
      try {
        return Health.up()
            .withDetail("redis", "Connected")
            .build();
      } catch (Exception e) {
        return Health.down()
            .withDetail("redis", "Disconnected")
            .withDetail("error", e.getMessage())
            .build();
      }
    };
  }
}