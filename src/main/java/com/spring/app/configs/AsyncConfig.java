package com.spring.app.configs;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.spring.app.configs.properties.AsyncProperties;

import lombok.RequiredArgsConstructor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
@RequiredArgsConstructor
public class AsyncConfig {

  private final AsyncProperties asyncProperties;

  private final static String TASK_THREAD_NAME_PREFIX = "ASYNC-TASK-";
  private final static String EMAIL_THREAD_NAME_PREFIX = "EMAIL-TASK-";

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
   * One virtual thread per e-mail: sending is almost entirely waiting on the
   * SMTP server, so there is nothing worth pooling. The number of concurrent
   * SMTP sessions is capped in {@link com.spring.app.shared.services.MailService}
   * instead, around the resource that is actually scarce.
   */
  @Bean(name = "emailExecutor")
  Executor emailExecutor() {
    SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor(EMAIL_THREAD_NAME_PREFIX);
    executor.setVirtualThreads(true);
    return executor;
  }
}