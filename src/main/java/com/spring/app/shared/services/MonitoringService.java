package com.spring.app.shared.services;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Authentication metrics. Prometheus appends {@code _total} to counters.
 * <ul>
 * <li>{@code auth.login.success}</li>
 * <li>{@code auth.login.failed{reason=bad_credentials|not_active|locked}}</li>
 * <li>{@code auth.registration.attempts} - successful registrations</li>
 * <li>{@code auth.otp.failed{purpose=activation|password_reset}}</li>
 * <li>{@code auth.token.refresh{result=success|failure}}</li>
 * <li>{@code auth.logout}</li>
 * <li>{@code auth.oauth2.login{result=success|failure}}</li>
 * <li>{@code auth.ratelimit.rejected{bucket}}</li>
 * <li>timers {@code auth.login.duration}, {@code auth.registration.duration}</li>
 * </ul>
 */
@Service
@Slf4j
public class MonitoringService {

  private static final String RESULT = "result";

  private final MeterRegistry meterRegistry;

  private final Counter loginSuccessCounter;
  private final Counter registrationCounter;
  private final Counter logoutCounter;
  private final Timer loginTimer;
  private final Timer registrationTimer;

  public MonitoringService(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
    this.loginSuccessCounter = Counter.builder("auth.login.success")
        .description("Successful logins")
        .register(meterRegistry);
    this.registrationCounter = Counter.builder("auth.registration.attempts")
        .description("Successful registrations")
        .register(meterRegistry);
    this.logoutCounter = Counter.builder("auth.logout")
        .description("Logouts")
        .register(meterRegistry);
    this.loginTimer = Timer.builder("auth.login.duration")
        .description("Login request duration")
        .register(meterRegistry);
    this.registrationTimer = Timer.builder("auth.registration.duration")
        .description("Registration request duration")
        .register(meterRegistry);
  }

  public void recordLoginSuccess() {
    loginSuccessCounter.increment();
  }

  public void recordLoginFailure(String reason) {
    tagged("auth.login.failed", "Failed logins", "reason", reason).increment();
  }

  public void incrementRegistrationAttempts() {
    registrationCounter.increment();
  }

  public void recordOtpFailure(String purpose) {
    tagged("auth.otp.failed", "Rejected OTPs", "purpose", purpose).increment();
  }

  public void recordTokenRefresh(boolean success) {
    tagged("auth.token.refresh", "Refresh-token exchanges", RESULT, outcome(success)).increment();
  }

  public void recordLogout() {
    logoutCounter.increment();
  }

  public void recordOAuth2Login(boolean success) {
    tagged("auth.oauth2.login", "OAuth2 logins", RESULT, outcome(success)).increment();
  }

  public void recordRateLimitRejection(String bucket) {
    tagged("auth.ratelimit.rejected", "Requests refused by a rate limit", "bucket", bucket).increment();
  }

  public Timer.Sample startLoginTimer() {
    return Timer.start(meterRegistry);
  }

  public void stopLoginTimer(Timer.Sample sample) {
    sample.stop(loginTimer);
  }

  public Timer.Sample startRegistrationTimer() {
    return Timer.start(meterRegistry);
  }

  public void stopRegistrationTimer(Timer.Sample sample) {
    sample.stop(registrationTimer);
  }

  public void recordLoginDuration(long duration, TimeUnit unit) {
    loginTimer.record(duration, unit);
  }

  public void recordRegistrationDuration(long duration, TimeUnit unit) {
    registrationTimer.record(duration, unit);
  }

  /** Micrometer returns the already-registered counter for the same name and tags. */
  private Counter tagged(String name, String description, String tagKey, String tagValue) {
    return Counter.builder(name).description(description).tag(tagKey, tagValue).register(meterRegistry);
  }

  private static String outcome(boolean success) {
    return success ? "success" : "failure";
  }
}
