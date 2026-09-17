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
  private final static String EMAIL_THREAD_NAME_PREFIX = "EMAIL-TASK-";

  @Bean(name = "taskExecutor")
  Executor taskExecutor() {
    return buildExecutor(asyncProperties.getTask(), TASK_THREAD_NAME_PREFIX);
  }

  @Bean(name = "emailExecutor")
  Executor emailExecutor() {
    return buildExecutor(asyncProperties.getEmail(), EMAIL_THREAD_NAME_PREFIX);
  }

  private Executor buildExecutor(AsyncProperties.Pool pool, String threadNamePrefix) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(pool.getCoreSize());
    executor.setMaxPoolSize(pool.getMaxSize());
    executor.setQueueCapacity(pool.getQueueCapacity());
    executor.setThreadNamePrefix(threadNamePrefix);
    executor.initialize();
    return executor;
  }
}