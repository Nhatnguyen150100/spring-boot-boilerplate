package com.spring.app.configs.properties;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

class FileStoragePropertiesTest {

  private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void defaultsAreValid() {
    assertThat(VALIDATOR.validate(new FileStorageProperties())).isEmpty();
  }

  @Test
  void storedFilesAreWorldReadableByDefault() {
    assertThat(new FileStorageProperties().getFilePermissions()).isEqualTo("rw-r--r--");
  }

  @Test
  void rejectsMalformedFilePermissions() {
    FileStorageProperties properties = new FileStorageProperties();
    properties.setFilePermissions("0644");

    assertThat(violatedPaths(properties)).containsExactly("filePermissions");
  }

  @Test
  void rejectsEmptyAllowlistsAndBlankEntries() {
    FileStorageProperties properties = new FileStorageProperties();
    properties.setAllowedContentTypes(List.of());
    properties.setAllowedExtensions(List.of(" "));

    assertThat(violatedPaths(properties))
      .anyMatch(path -> path.equals("allowedContentTypes"))
      .anyMatch(path -> path.startsWith("allowedExtensions"));
  }

  @Test
  void bindsCommaSeparatedValuesAsUsedInDotEnv() {
    Map<String, String> source = Map.of(
      "application.file.storage.upload-dir", "files",
      "application.file.storage.allowed-content-types", "application/zip,text/csv",
      "application.file.storage.allowed-extensions", "zip,csv",
      "application.file.storage.file-permissions", "rw-r-----");

    FileStorageProperties bound = new Binder(new MapConfigurationPropertySource(source))
      .bind("application.file.storage", Bindable.of(FileStorageProperties.class))
      .get();

    assertThat(bound.getAllowedContentTypes()).containsExactly("application/zip", "text/csv");
    assertThat(bound.getAllowedExtensions()).containsExactly("zip", "csv");
    assertThat(bound.getFilePermissions()).isEqualTo("rw-r-----");
  }

  private static List<String> violatedPaths(FileStorageProperties properties) {
    return VALIDATOR.validate(properties).stream()
      .map(violation -> violation.getPropertyPath().toString())
      .toList();
  }
}
