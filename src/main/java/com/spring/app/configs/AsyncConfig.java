package com.spring.app.configs;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;

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
   * for the OTP again. On shutdown, running and queued sends get up to
   * {@value #EMAIL_AWAIT_TERMINATION_SECONDS}s to finish.
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

  private static RejectedExecutionHandler logAndDrop() {
    return (task, pool) -> log.warn(
        "E-mail executor saturated or shutting down (active={}, queued={}); e-mail dropped",
        pool.getActiveCount(), pool.getQueue().size());
  }
}
