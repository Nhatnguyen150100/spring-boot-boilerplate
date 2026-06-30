package com.spring.app.configs;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
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
  HealthIndicator redisHealthIndicator(RedisConnectionFactory redisConnectionFactory) {
    return () -> {
      try (RedisConnection connection = redisConnectionFactory.getConnection()) {
        String pong = connection.ping();
        return Health.up()
            .withDetail("redis", "Connected")
            .withDetail("ping", pong)
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