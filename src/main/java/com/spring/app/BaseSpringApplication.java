package com.spring.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class BaseSpringApplication {

  public static void main(String[] args) {
    SpringApplication.run(BaseSpringApplication.class, args);
  }

}