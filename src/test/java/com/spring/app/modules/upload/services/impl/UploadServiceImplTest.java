package com.spring.app.modules.upload.services.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import com.spring.app.common.response.BaseResponse;
import com.spring.app.configs.properties.FileStorageProperties;
import com.spring.app.exceptions.BadRequestException;
import com.spring.app.modules.upload.services.FileChecksumRegistry;

/**
 * Runs against a real temp directory, without a Spring context. The registry
 * gets a direct executor, so the startup index is complete once
 * {@code init()} returns.
 */
class UploadServiceImplTest {

  @TempDir
  Path dir;

  @Test
  void storesNewFile() throws IOException {
    UploadServiceImpl service = newService();

    BaseResponse<?> body = body(service.storeFile(file("a.txt", "hello")));

    assertThat(body.getData()).isEqualTo("a.txt");
    assertThat(dir.resolve("a.txt")).hasContent("hello");
    assertNoTempFilesLeft();
  }

  @Test
  void duplicateContentIsNotStoredAgain() throws IOException {
    UploadServiceImpl service = newService();
    service.storeFile(file("a.txt", "hello"));

    BaseResponse<?> body = body(service.storeFile(file("b.txt", "hello")));

    assertThat(body.getMessage()).isEqualTo("File with identical content already exists");
    assertThat(body.getData()).isEqualTo("a.txt");
    assertThat(dir.resolve("b.txt")).doesNotExist();
    assertNoTempFilesLeft();
  }

  @Test
  void contentCanBeUploadedAgainAfterItsFileIsDeleted() {
    UploadServiceImpl service = newService();
    service.storeFile(file("a.txt", "hello"));
    service.deleteFile("a.txt");

    BaseResponse<?> body = body(service.storeFile(file("b.txt", "hello")));

    assertThat(body.getData()).isEqualTo("b.txt");
    assertThat(dir.resolve("b.txt")).hasContent("hello");
  }

  @Test
  void overwrittenContentNoLongerCountsAsDuplicate() {
    UploadServiceImpl service = newService();
    service.storeFile(file("a.txt", "v1"));
    service.storeFile(file("a.txt", "v2"));

    BaseResponse<?> body = body(service.storeFile(file("b.txt", "v1")));

    assertThat(body.getData()).isEqualTo("b.txt");
    assertThat(dir.resolve("a.txt")).hasContent("v2");
  }

  @Test
  void filesStoredBeforeStartupAreRecognised() throws IOException {
    Files.writeString(dir.resolve("old.txt"), "hello");
    UploadServiceImpl service = newService();

    BaseResponse<?> body = body(service.storeFile(file("new.txt", "hello")));

    assertThat(body.getData()).isEqualTo("old.txt");
    assertThat(dir.resolve("new.txt")).doesNotExist();
  }

  @Test
  void rejectsUploadWithoutFileName() {
    UploadServiceImpl service = newService();

    assertThatThrownBy(() -> service.storeFile(file("", "hello")))
      .isInstanceOf(BadRequestException.class)
      .hasMessage("File name is missing");
  }

  private UploadServiceImpl newService() {
    FileStorageProperties properties = new FileStorageProperties();
    properties.setUploadDir(dir.toString());
    UploadServiceImpl service = new UploadServiceImpl(properties, new FileChecksumRegistry(Runnable::run));
    service.init();
    return service;
  }

  private static MockMultipartFile file(String name, String content) {
    return new MockMultipartFile("file", name, "text/plain", content.getBytes(StandardCharsets.UTF_8));
  }

  private static BaseResponse<?> body(ResponseEntity<?> response) {
    return (BaseResponse<?>) response.getBody();
  }

  private void assertNoTempFilesLeft() throws IOException {
    try (Stream<Path> files = Files.list(dir)) {
      assertThat(files.map(file -> file.getFileName().toString()))
        .noneMatch(name -> name.startsWith(".upload-"));
    }
  }
}
