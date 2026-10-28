package com.spring.app.configs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Builds only the Redis part of the context. No Redis server is needed: the
 * Lettuce factory does not connect until first use, and the fail-fast ping is
 * switched off.
 */
class RedisConfigTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
      .withUserConfiguration(RedisConfig.class)
      .withPropertyValues("application.redis.fail-fast=false");

  @Test
  void connectionFactoryHonoursSslAndTimeoutsFromSpringDataRedisProperties() {
    contextRunner
        .withPropertyValues(
            "spring.data.redis.ssl.enabled=true",
            "spring.data.redis.timeout=1500ms",
            "spring.data.redis.connect-timeout=700ms")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(RedisConnectionFactory.class);

          LettuceClientConfiguration client = context.getBean(LettuceConnectionFactory.class)
              .getClientConfiguration();

          assertThat(client.isUseSsl()).isTrue();
          assertThat(client.getCommandTimeout()).isEqualTo(Duration.ofMillis(1500));
          assertThat(client.getClientOptions()).isPresent();
          assertThat(client.getClientOptions().get().getSocketOptions().getConnectTimeout())
              .isEqualTo(Duration.ofMillis(700));
        });
  }

  @Test
  void jsonTemplateAndBootsStringTemplateAreBothAvailable() {
    contextRunner.run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context).hasBean("redisTemplate");
      assertThat(context).hasSingleBean(StringRedisTemplate.class);
    });
  }
}
