package com.spring.app.configs;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.spring.app.configs.properties.AsyncProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
@EnableAsync
@RequiredArgsConstructor
public class AsyncConfig {

  private final AsyncProperties asyncProperties;

  private static final String TASK_THREAD_NAME_PREFIX = "ASYNC-TASK-";
  static final String EMAIL_THREAD_NAME_PREFIX = "EMAIL-TASK-";

  /** Upper bound on how long shutdown waits for queued / running sends. */
  static final int EMAIL_AWAIT_TERMINATION_SECONDS = 30;

  /**
   * Lifecycle phase of {@link EmailExecutorDrain}. Lifecycles stop from the
   * highest phase down, so this phase must sit strictly between:
   * <ul>
   * <li>Boot's web-server graceful-shutdown ({@code DEFAULT_PHASE - 1024}) and
   * web-server stop ({@code DEFAULT_PHASE - 2048}) lifecycles - stopped first,
   * so requests still in flight can queue their OTP mail before we drain;
   * and</li>
   * <li>LettuceConnectionFactory (phase 0) - stopped after us, so queued sends
   * can still store their OTP in Redis.</li>
   * </ul>
   */
  static final int EMAIL_DRAIN_PHASE = Integer.MAX_VALUE / 4;

  /**
   * General-purpose pool ({@code application.async.task.*}). Nothing names it
   * today; it is kept on purpose because the bean called {@code taskExecutor}
   * is the default executor Spring picks for any bare {@code @Async} method
   * (one without an executor qualifier), so a future {@code @Async} gets a
   * bounded pool instead of Spring's fallback. Do not delete it as unused.
   * E-mail uses its own {@code emailExecutor} below.
   */
  @Bean(name = "taskExecutor")
  Executor taskExecutor() {
    AsyncProperties.Pool pool = asyncProperties.getTask();
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(pool.getCoreSize());
    executor.setMaxPoolSize(pool.getMaxSize());
    executor.setQueueCapacity(pool.getQueueCapacity());
    executor.setThreadNamePrefix(TASK_THREAD_NAME_PREFIX);
    executor.initialize();
    return executor;
  }

  /**
   * Bounded pool of PLATFORM threads for outgoing e-mail.
   *
   * <p>
   * Not virtual threads: jakarta.mail's SMTPTransport does its socket I/O
   * inside synchronized methods, which on Java 21 pins a virtual thread to its
   * carrier. HTTP requests run on virtual threads too
   * ({@code spring.threads.virtual.enabled}), so a few slow SMTP servers would
   * starve request handling. A fixed pool keeps that blocking off the carriers;
   * its max-size is also the cap on concurrent SMTP sessions.
   * </p>
   *
   * <p>
   * When pool and queue are full the e-mail is logged and dropped rather than
   * blocking or failing the HTTP request that triggered it - the user can ask
   * for the OTP again.
   * </p>
   *
   * <p>
   * Shutdown is driven by {@link #emailExecutorDrain}, not by this executor's
   * own destroy(): with wait-for-tasks enabled ThreadPoolTaskExecutor only
   * drains at bean destruction, after Redis has already been stopped. The
   * drain lifecycle stops taking new sends once the web server has stopped and
   * gives running and queued sends up to
   * {@value #EMAIL_AWAIT_TERMINATION_SECONDS}s to finish while Redis is still
   * up; anything left after that fails or is lost.
   * </p>
   */
  @Bean(name = "emailExecutor")
  ThreadPoolTaskExecutor emailExecutor() {
    AsyncProperties.Pool pool = asyncProperties.getEmail();
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(pool.getCoreSize());
    executor.setMaxPoolSize(pool.getMaxSize());
    executor.setQueueCapacity(pool.getQueueCapacity());
    executor.setThreadNamePrefix(EMAIL_THREAD_NAME_PREFIX);
    executor.setRejectedExecutionHandler(logAndDrop());
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(EMAIL_AWAIT_TERMINATION_SECONDS);
    executor.initialize();
    return executor;
  }

  /** Drains {@code emailExecutor} before Redis stops; see {@link #EMAIL_DRAIN_PHASE}. */
  @Bean
  EmailExecutorDrain emailExecutorDrain(@Qualifier("emailExecutor") ThreadPoolTaskExecutor emailExecutor) {
    return new EmailExecutorDrain(emailExecutor);
  }

  private static RejectedExecutionHandler logAndDrop() {
    return (task, pool) -> log.warn(
        "E-mail executor saturated or shutting down (active={}, queued={}); e-mail dropped",
        pool.getActiveCount(), pool.getQueue().size());
  }

  /**
   * Shuts {@code emailExecutor} down and waits for running and queued sends in
   * lifecycle phase {@link #EMAIL_DRAIN_PHASE}, i.e. after the web server and
   * before Redis. Sends submitted after this point are rejected (logged and
   * dropped).
   */
  static final class EmailExecutorDrain implements SmartLifecycle {

    private final ThreadPoolTaskExecutor emailExecutor;
    private volatile boolean running;

    EmailExecutorDrain(ThreadPoolTaskExecutor emailExecutor) {
      this.emailExecutor = emailExecutor;
    }

    @Override
    public void start() {
      running = true;
    }

    @Override
    public void stop() {
      running = false;
      ThreadPoolExecutor pool = emailExecutor.getThreadPoolExecutor();
      pool.shutdown();
      try {
        if (!pool.awaitTermination(EMAIL_AWAIT_TERMINATION_SECONDS, TimeUnit.SECONDS)) {
          log.warn("E-mail executor did not finish within {}s (active={}, queued={}); those e-mails may be lost",
              EMAIL_AWAIT_TERMINATION_SECONDS, pool.getActiveCount(), pool.getQueue().size());
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.warn("Interrupted while draining the e-mail executor (active={}, queued={})",
            pool.getActiveCount(), pool.getQueue().size());
      }
    }

    @Override
    public boolean isRunning() {
      return running;
    }

    @Override
    public int getPhase() {
      return EMAIL_DRAIN_PHASE;
    }
  }
}
