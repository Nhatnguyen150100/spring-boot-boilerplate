package com.spring.app.configs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.spring.app.configs.properties.AsyncProperties;

/** Exercises the real emailExecutor bean method, without a Spring context. */
class AsyncConfigTest {

  private ThreadPoolTaskExecutor executor;

  @AfterEach
  void tearDown() {
    if (executor != null) {
      executor.shutdown();
    }
  }

  private ThreadPoolTaskExecutor emailExecutor(int core, int max, int queue) {
    AsyncProperties properties = new AsyncProperties();
    properties.setEmail(new AsyncProperties.Pool(core, max, queue));
    executor = new AsyncConfig(properties).emailExecutor();
    return executor;
  }

  @Test
  void emailExecutorIsSizedFromProperties() {
    ThreadPoolTaskExecutor ex = emailExecutor(2, 4, 7);

    assertThat(ex.getCorePoolSize()).isEqualTo(2);
    assertThat(ex.getMaxPoolSize()).isEqualTo(4);
    assertThat(ex.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(7);
  }

  @Test
  void runsOnNamedPlatformThreadsNotVirtualThreads() throws Exception {
    ThreadPoolTaskExecutor ex = emailExecutor(1, 1, 1);
    Callable<Thread> currentThread = Thread::currentThread;

    Thread worker = ex.submit(currentThread).get(5, TimeUnit.SECONDS);

    assertThat(worker.isVirtual()).isFalse();
    assertThat(worker.getName()).startsWith(AsyncConfig.EMAIL_THREAD_NAME_PREFIX);
  }

  @Test
  void saturatedPoolDropsTheTaskInsteadOfThrowingAtTheCaller() throws Exception {
    ThreadPoolTaskExecutor ex = emailExecutor(1, 1, 1);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ex.execute(() -> {
      started.countDown();
      awaitQuietly(release);
    });
    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
    ex.execute(() -> {
    }); // occupies the single queue slot

    AtomicBoolean ran = new AtomicBoolean(false);
    assertThatCode(() -> ex.execute(() -> ran.set(true))).doesNotThrowAnyException();

    release.countDown();
    ex.shutdown();
    assertThat(ran).isFalse();
  }

  @Test
  void shutdownWaitsForRunningSends() throws Exception {
    ThreadPoolTaskExecutor ex = emailExecutor(1, 1, 1);
    CountDownLatch started = new CountDownLatch(1);
    AtomicBoolean finished = new AtomicBoolean(false);
    ex.execute(() -> {
      started.countDown();
      finished.set(sleptWithoutInterruption(300));
    });
    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

    ex.shutdown();

    assertThat(finished).isTrue();
  }

  @Test
  void drainStopsAfterTheWebServerButBeforeRedis() {
    AsyncConfig.EmailExecutorDrain drain = new AsyncConfig(new AsyncProperties())
        .emailExecutorDrain(emailExecutor(1, 1, 1));

    // Lettuce's connection factory stops in phase 0; Boot's graceful-shutdown
    // and web-server lifecycles stop in DEFAULT_PHASE - 1024 / - 2048.
    assertThat(drain.getPhase())
        .isGreaterThan(0)
        .isLessThan(SmartLifecycle.DEFAULT_PHASE - 2048);
  }

  @Test
  void drainIsRunningOnlyBetweenStartAndStop() {
    AsyncConfig.EmailExecutorDrain drain = new AsyncConfig(new AsyncProperties())
        .emailExecutorDrain(emailExecutor(1, 1, 1));

    assertThat(drain.isRunning()).isFalse();
    drain.start();
    assertThat(drain.isRunning()).isTrue();
    drain.stop();
    assertThat(drain.isRunning()).isFalse();
  }

  @Test
  void drainStopWaitsForRunningAndQueuedSends() throws Exception {
    ThreadPoolTaskExecutor ex = emailExecutor(1, 1, 1);
    AsyncConfig.EmailExecutorDrain drain = new AsyncConfig(new AsyncProperties()).emailExecutorDrain(ex);
    drain.start();
    CountDownLatch started = new CountDownLatch(1);
    AtomicBoolean runningFinished = new AtomicBoolean(false);
    AtomicBoolean queuedRan = new AtomicBoolean(false);
    ex.execute(() -> {
      started.countDown();
      runningFinished.set(sleptWithoutInterruption(300));
    });
    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
    ex.execute(() -> queuedRan.set(true)); // waits in the queue

    drain.stop();

    assertThat(runningFinished).isTrue();
    assertThat(queuedRan).isTrue();
    assertThat(ex.getThreadPoolExecutor().isTerminated()).isTrue();
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static boolean sleptWithoutInterruption(long millis) {
    try {
      Thread.sleep(millis);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
