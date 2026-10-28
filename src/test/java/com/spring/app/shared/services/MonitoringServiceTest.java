package com.spring.app.shared.services;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MonitoringServiceTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final MonitoringService monitoring = new MonitoringService(registry);

  private double count(String name, String... tags) {
    Counter counter = registry.find(name).tags(tags).counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  void loginSuccessHasItsOwnNameAndTheMisleadingAttemptsCounterIsGone() {
    monitoring.recordLoginSuccess();

    assertThat(count("auth.login.success")).isEqualTo(1.0);
    assertThat(registry.find("auth.login.attempts").counter()).isNull();
  }

  @Test
  void loginFailuresAreTaggedByReason() {
    monitoring.recordLoginFailure("bad_credentials");
    monitoring.recordLoginFailure("bad_credentials");
    monitoring.recordLoginFailure("locked");

    assertThat(count("auth.login.failed", "reason", "bad_credentials")).isEqualTo(2.0);
    assertThat(count("auth.login.failed", "reason", "locked")).isEqualTo(1.0);
  }

  @Test
  void otpFailuresAreTaggedByPurpose() {
    monitoring.recordOtpFailure("activation");
    monitoring.recordOtpFailure("password_reset");

    assertThat(count("auth.otp.failed", "purpose", "activation")).isEqualTo(1.0);
    assertThat(count("auth.otp.failed", "purpose", "password_reset")).isEqualTo(1.0);
  }

  @Test
  void refreshAndOAuth2AreTaggedByResult() {
    monitoring.recordTokenRefresh(true);
    monitoring.recordTokenRefresh(false);
    monitoring.recordOAuth2Login(false);

    assertThat(count("auth.token.refresh", "result", "success")).isEqualTo(1.0);
    assertThat(count("auth.token.refresh", "result", "failure")).isEqualTo(1.0);
    assertThat(count("auth.oauth2.login", "result", "failure")).isEqualTo(1.0);
  }

  @Test
  void logoutAndRateLimitRejectionsAreCounted() {
    monitoring.recordLogout();
    monitoring.recordRateLimitRejection("login");

    assertThat(count("auth.logout")).isEqualTo(1.0);
    assertThat(count("auth.ratelimit.rejected", "bucket", "login")).isEqualTo(1.0);
  }
}
