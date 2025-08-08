package com.spring.app.configs.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Component
@ConfigurationProperties(prefix = "spring.file.storage")
public class FileStorageProperties {
  @NotBlank(message = "Upload directory is required")
  private String uploadDir;
}
