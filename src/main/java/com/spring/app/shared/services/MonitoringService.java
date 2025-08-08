package com.spring.app.shared.services;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class MonitoringService {

  private final MeterRegistry meterRegistry;

  private final Counter loginAttemptsCounter;
  private final Counter registrationCounter;
  private final Timer loginTimer;
  private final Timer registrationTimer;

  public MonitoringService(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
    this.loginAttemptsCounter = Counter.builder("auth.login.attempts")
        .description("Number of login attempts")
        .register(meterRegistry);
    this.registrationCounter = Counter.builder("auth.registration.attempts")
        .description("Number of registration attempts")
        .register(meterRegistry);
    this.loginTimer = Timer.builder("auth.login.duration")
        .description("Login request duration")
        .register(meterRegistry);
    this.registrationTimer = Timer.builder("auth.registration.duration")
        .description("Registration request duration")
        .register(meterRegistry);
  }

  public void incrementLoginAttempts() {
    loginAttemptsCounter.increment();
  }

  public void incrementRegistrationAttempts() {
    registrationCounter.increment();
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
}