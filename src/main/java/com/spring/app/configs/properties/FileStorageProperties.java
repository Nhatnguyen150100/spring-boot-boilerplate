package com.spring.app.configs.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Where uploaded files are written to. */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "application.file.storage", ignoreUnknownFields = false)
public class FileStorageProperties {

  @NotBlank(message = "application.file.storage.upload-dir is required")
  private String uploadDir = "uploads";
}
