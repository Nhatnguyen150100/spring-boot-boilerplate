package com.spring.app.configs.properties;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** Where uploaded files are written to, and which uploads are accepted. */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "application.file.storage", ignoreUnknownFields = false)
public class FileStorageProperties {

  /** Root folder; files live in {@code <upload-dir>/<userId>/}. */
  @NotBlank(message = "application.file.storage.upload-dir is required")
  private String uploadDir = "uploads";

  /**
   * Accepted Content-Type values ({@code type/subtype}, parameters ignored,
   * case-insensitive). An upload must match this AND {@link #allowedExtensions}.
   */
  @NotEmpty(message = "application.file.storage.allowed-content-types must not be empty")
  private List<@NotBlank String> allowedContentTypes = new ArrayList<>(List.of(
    "image/png", "image/jpeg", "image/gif", "image/webp", "application/pdf", "text/plain"));

  /** Accepted file-name extensions, without the dot, case-insensitive. */
  @NotEmpty(message = "application.file.storage.allowed-extensions must not be empty")
  private List<@NotBlank String> allowedExtensions = new ArrayList<>(List.of(
    "png", "jpg", "jpeg", "gif", "webp", "pdf", "txt"));

  /**
   * POSIX permissions given to stored files, in {@code ls -l} form. Ignored on
   * file systems without POSIX permissions.
   */
  @NotBlank(message = "application.file.storage.file-permissions is required")
  @Pattern(regexp = "([r-][w-][x-]){3}",
    message = "application.file.storage.file-permissions must look like rw-r--r--")
  private String filePermissions = "rw-r--r--";
}
