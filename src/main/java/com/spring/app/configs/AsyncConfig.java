package com.spring.app.configs;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

  private final static int EMAIL_CORE_POOL_SIZE = 2;
  private final static int EMAIL_MAX_POOL_SIZE = 5;
  private final static int EMAIL_QUEUE_CAPACITY = 10;
  private final static String EMAIL_THREAD_NAME_PREFIX = "EMAIL-TASK-";

  @Bean(name = "taskExecutor")
  Executor taskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

    final int TASK_CORE_POOL_SIZE = asyncProperties.getCoreSize();
    final int TASK_MAX_POOL_SIZE = asyncProperties.getMaxSize();
    final int TASK_QUEUE_CAPACITY = asyncProperties.getQueueCapacity();

    executor.setCorePoolSize(TASK_CORE_POOL_SIZE);
    executor.setMaxPoolSize(TASK_MAX_POOL_SIZE);
    executor.setQueueCapacity(TASK_QUEUE_CAPACITY);
    executor.setThreadNamePrefix(TASK_THREAD_NAME_PREFIX);
    executor.initialize();
    return executor;
  }

  @Bean(name = "emailExecutor")
  Executor emailExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(EMAIL_CORE_POOL_SIZE);
    executor.setMaxPoolSize(EMAIL_MAX_POOL_SIZE);
    executor.setQueueCapacity(EMAIL_QUEUE_CAPACITY);
    executor.setThreadNamePrefix(EMAIL_THREAD_NAME_PREFIX);
    executor.initialize();
    return executor;
  }
}