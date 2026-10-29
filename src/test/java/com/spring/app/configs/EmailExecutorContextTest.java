package com.spring.app.configs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.spring.app.configs.properties.AsyncProperties;
import com.spring.app.shared.interfaces.MailServiceInterface;
import com.spring.app.modules.auth.services.OtpAttemptService;
import com.spring.app.shared.services.OtpEmailService;
import com.spring.app.utils.OtpFunctionUtil;

/**
 * The real AsyncConfig (with its {@code @EnableAsync}) and AsyncProperties
 * binding in a Spring context: proves {@code @Async("emailExecutor")} lands on
 * the bounded platform-thread pool, and that sends still queued at shutdown run
 * before a phase-0 lifecycle (Lettuce's connection factory) is stopped.
 */
class EmailExecutorContextTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(AsyncProperties.class)
  static class PropertiesConfig {
  }

  /** Stands in for LettuceConnectionFactory: a SmartLifecycle in phase 0. */
  static class FakeRedisLifecycle implements SmartLifecycle {

    final AtomicBoolean stopped = new AtomicBoolean(false);
    private volatile boolean running;

    @Override
    public void start() {
      running = true;
    }

    @Override
    public void stop() {
      stopped.set(true);
      running = false;
    }

    @Override
    public boolean isRunning() {
      return running;
    }

    @Override
    public int getPhase() {
      return 0;
    }
  }

  private final OtpFunctionUtil otpFunction = mock(OtpFunctionUtil.class);
  private final MailServiceInterface mailService = mock(MailServiceInterface.class);

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withUserConfiguration(PropertiesConfig.class, AsyncConfig.class, OtpEmailService.class)
      .withBean(OtpFunctionUtil.class, () -> otpFunction)
      .withBean(MailServiceInterface.class, () -> mailService)
      .withBean(OtpAttemptService.class, () -> mock(OtpAttemptService.class))
      .withPropertyValues(
          "application.async.email.core-size=1",
          "application.async.email.max-size=1",
          "application.async.email.queue-capacity=5");

  @Test
  void otpSendRunsOnTheEmailPoolPlatformThreads() throws Exception {
    when(otpFunction.generateOtp()).thenReturn("123456");
    AtomicReference<Thread> sender = new AtomicReference<>();
    CountDownLatch sent = new CountDownLatch(1);
    doAnswer(invocation -> {
      sender.set(Thread.currentThread());
      sent.countDown();
      return null;
    }).when(mailService).sendOtpEmail(anyString(), anyString());

    runner.run(context -> {
      assertThat(context).hasNotFailed();
      ThreadPoolTaskExecutor email = context.getBean("emailExecutor", ThreadPoolTaskExecutor.class);
      assertThat(email.getMaxPoolSize()).isEqualTo(1); // bound from application.async.email.*

      context.getBean(OtpEmailService.class).sendOtpEmailAsync("alice@example.com");

      assertThat(sent.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(sender.get()).isNotSameAs(Thread.currentThread());
      assertThat(sender.get().getName()).startsWith(AsyncConfig.EMAIL_THREAD_NAME_PREFIX);
      assertThat(sender.get().isVirtual()).isFalse();
    });
  }

  @Test
  void queuedSendsRunBeforeRedisIsStoppedAtShutdown() throws Exception {
    FakeRedisLifecycle redis = new FakeRedisLifecycle();
    CountDownLatch started = new CountDownLatch(1);
    AtomicBoolean queuedRan = new AtomicBoolean(false);
    AtomicBoolean queuedSawRedisStopped = new AtomicBoolean(true);

    runner.withBean(FakeRedisLifecycle.class, () -> redis).run(context -> {
      assertThat(context).hasNotFailed();
      ThreadPoolTaskExecutor email = context.getBean("emailExecutor", ThreadPoolTaskExecutor.class);
      email.execute(() -> {
        started.countDown();
        sleepQuietly(300);
      });
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      email.execute(() -> {
        queuedSawRedisStopped.set(redis.stopped.get());
        queuedRan.set(true);
      });
    }); // run() closes the context here

    assertThat(redis.stopped).isTrue();
    assertThat(queuedRan).isTrue();
    assertThat(queuedSawRedisStopped).isFalse();
  }

  private static void sleepQuietly(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
