package com.spring.app.modules.upload.services.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import com.spring.app.common.response.BaseResponse;
import com.spring.app.configs.properties.FileStorageProperties;
import com.spring.app.exceptions.BadRequestException;
import com.spring.app.exceptions.ResourceNotFoundException;

/**
 * Runs against a real temp directory, without a Spring context. The caller's
 * identity comes from {@link #currentUser}; a test switches it to act as
 * another user.
 */
class UploadServiceImplTest {

  private static final String USER_A = "11111111-1111-1111-1111-111111111111";
  private static final String USER_B = "22222222-2222-2222-2222-222222222222";

  @TempDir
  Path dir;

  private final AtomicReference<String> currentUser = new AtomicReference<>(USER_A);
  private final FileStorageProperties properties = new FileStorageProperties();

  @Test
  void storesFileInTheCallersFolder() throws IOException {
    UploadServiceImpl service = newService();

    BaseResponse<?> body = body(service.storeFile(file("a.txt", "hello")));

    assertThat(body.getMessage()).isEqualTo("File uploaded successfully");
    assertThat(body.getData()).isEqualTo("a.txt");
    assertThat(fileOf(USER_A, "a.txt")).hasContent("hello");
    assertThat(dir.resolve("a.txt")).doesNotExist();
    assertTempDirEmpty();
  }

  @Test
  void sameFileNameFromTwoUsersDoesNotCollide() {
    UploadServiceImpl service = newService();
    service.storeFile(file("report.txt", "from A"));

    currentUser.set(USER_B);
    service.storeFile(file("report.txt", "from B"));

    assertThat(fileOf(USER_A, "report.txt")).hasContent("from A");
    assertThat(fileOf(USER_B, "report.txt")).hasContent("from B");
  }

  @Test
  void userCannotDownloadAnotherUsersFile() {
    UploadServiceImpl service = newService();
    service.storeFile(file("report.txt", "secret"));

    currentUser.set(USER_B);

    assertThatThrownBy(() -> service.downFile("report.txt"))
      .isInstanceOf(ResourceNotFoundException.class)
      .hasMessage("File not found");
  }

  @Test
  void userCannotDeleteAnotherUsersFile() {
    UploadServiceImpl service = newService();
    service.storeFile(file("report.txt", "secret"));

    currentUser.set(USER_B);

    assertThatThrownBy(() -> service.deleteFile("report.txt"))
      .isInstanceOf(ResourceNotFoundException.class)
      .hasMessage("File not found");
    assertThat(fileOf(USER_A, "report.txt")).hasContent("secret");
  }

  @Test
  void pathsEscapingTheCallersFolderAreRejected() {
    UploadServiceImpl service = newService();
    service.storeFile(file("report.txt", "secret"));

    currentUser.set(USER_B);

    assertThatThrownBy(() -> service.downFile("../" + USER_A + "/report.txt"))
      .isInstanceOf(BadRequestException.class)
      .hasMessage("Invalid file path");
    assertThatThrownBy(() -> service.deleteFile("../" + USER_A + "/report.txt"))
      .isInstanceOf(BadRequestException.class)
      .hasMessage("Invalid file path");
    assertThatThrownBy(() -> service.downFile("../" + UploadServiceImpl.TEMP_DIR_NAME + "/upload-1.tmp"))
      .isInstanceOf(BadRequestException.class)
      .hasMessage("Invalid file path");
    assertThat(fileOf(USER_A, "report.txt")).hasContent("secret");
  }

  @Test
  void reuploadWithContentOfAnotherFileIsStoredUnderTheRequestedName() {
    UploadServiceImpl service = newService();
    service.storeFile(file("a.txt", "v1"));
    service.storeFile(file("b.txt", "hello"));

    BaseResponse<?> body = body(service.storeFile(file("a.txt", "hello")));

    assertThat(body.getMessage()).isEqualTo("File uploaded successfully");
    assertThat(body.getData()).isEqualTo("a.txt");
    assertThat(fileOf(USER_A, "a.txt")).hasContent("hello");
    assertThat(fileOf(USER_A, "b.txt")).hasContent("hello");
  }

  @Test
  void deletingOneOfTwoIdenticalFilesKeepsTheOther() {
    UploadServiceImpl service = newService();
    service.storeFile(file("a.txt", "hello"));
    service.storeFile(file("b.txt", "hello"));

    service.deleteFile("a.txt");

    assertThat(fileOf(USER_A, "a.txt")).doesNotExist();
    assertThat(fileOf(USER_A, "b.txt")).hasContent("hello");
  }

  @Test
  void downloadReturnsTheCallersFile() throws IOException {
    UploadServiceImpl service = newService();
    service.storeFile(file("a.txt", "hello"));

    ResponseEntity<Resource> response = service.downFile("a.txt");

    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
    assertThat(response.getBody().getContentAsString(StandardCharsets.UTF_8)).isEqualTo("hello");
  }

  @Test
  void downloadingMissingFileIsNotFound() {
    UploadServiceImpl service = newService();

    assertThatThrownBy(() -> service.downFile("missing.txt"))
      .isInstanceOf(ResourceNotFoundException.class)
      .hasMessage("File not found");
  }

  @Test
  void deletingMissingFileIsNotFound() {
    UploadServiceImpl service = newService();

    assertThatThrownBy(() -> service.deleteFile("missing.txt"))
      .isInstanceOf(ResourceNotFoundException.class)
      .hasMessage("File not found");
  }

  @Test
  void rejectsUploadWithoutFileName() {
    UploadServiceImpl service = newService();

    assertThatThrownBy(() -> service.storeFile(file("", "hello")))
      .isInstanceOf(BadRequestException.class)
      .hasMessage("File name is missing");
  }

  @Test
  void rejectsDisallowedContentType() {
    UploadServiceImpl service = newService();
    MockMultipartFile upload = new MockMultipartFile("file", "a.txt", "application/x-msdownload",
      "MZ".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> service.storeFile(upload))
      .isInstanceOf(BadRequestException.class)
      .hasMessage("File type not allowed");
    assertThat(fileOf(USER_A, "a.txt")).doesNotExist();
  }

  @Test
  void rejectsDisallowedExtension() {
    UploadServiceImpl service = newService();

    assertThatThrownBy(() -> service.storeFile(file("a.exe", "hello")))
      .isInstanceOf(BadRequestException.class)
      .hasMessage("File type not allowed");
    assertThat(fileOf(USER_A, "a.exe")).doesNotExist();
  }

  @Test
  void rejectsMissingContentType() {
    UploadServiceImpl service = newService();
    MockMultipartFile upload = new MockMultipartFile("file", "a.txt", null, "hello".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> service.storeFile(upload))
      .isInstanceOf(BadRequestException.class)
      .hasMessage("File type not allowed");
  }

  @Test
  void acceptsContentTypeParametersAndUpperCaseExtension() {
    UploadServiceImpl service = newService();
    MockMultipartFile upload = new MockMultipartFile("file", "NOTES.TXT", "Text/Plain; charset=UTF-8",
      "hello".getBytes(StandardCharsets.UTF_8));

    service.storeFile(upload);

    assertThat(fileOf(USER_A, "NOTES.TXT")).hasContent("hello");
  }

  @Test
  void allowlistIsConfigurable() {
    properties.setAllowedContentTypes(List.of("application/zip"));
    properties.setAllowedExtensions(List.of(".zip"));
    UploadServiceImpl service = newService();

    service.storeFile(new MockMultipartFile("file", "a.zip", "application/zip", new byte[] { 1 }));

    assertThat(fileOf(USER_A, "a.zip")).exists();
    assertThatThrownBy(() -> service.storeFile(file("a.txt", "hello")))
      .isInstanceOf(BadRequestException.class)
      .hasMessage("File type not allowed");
  }

  @Test
  void storedFileIsReadableByOtherOsUsersByDefault() throws IOException {
    assumeTrue(isPosix(), "file system has no POSIX permissions");
    UploadServiceImpl service = newService();

    service.storeFile(file("a.txt", "hello"));

    assertThat(Files.getPosixFilePermissions(fileOf(USER_A, "a.txt")))
      .isEqualTo(PosixFilePermissions.fromString("rw-r--r--"));
  }

  @Test
  void storedFilePermissionsAreConfigurable() throws IOException {
    assumeTrue(isPosix(), "file system has no POSIX permissions");
    properties.setFilePermissions("rw-r-----");
    UploadServiceImpl service = newService();

    service.storeFile(file("a.txt", "hello"));

    assertThat(Files.getPosixFilePermissions(fileOf(USER_A, "a.txt")))
      .isEqualTo(PosixFilePermissions.fromString("rw-r-----"));
  }

  private UploadServiceImpl newService() {
    properties.setUploadDir(dir.toString());
    UploadServiceImpl service = new UploadServiceImpl(properties, currentUser::get);
    service.init();
    return service;
  }

  private Path fileOf(String user, String name) {
    return dir.resolve(user).resolve(name);
  }

  private static boolean isPosix() {
    return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
  }

  private static MockMultipartFile file(String name, String content) {
    return new MockMultipartFile("file", name, "text/plain", content.getBytes(StandardCharsets.UTF_8));
  }

  private static BaseResponse<?> body(ResponseEntity<?> response) {
    return (BaseResponse<?>) response.getBody();
  }

  private void assertTempDirEmpty() throws IOException {
    try (Stream<Path> files = Files.list(dir.resolve(UploadServiceImpl.TEMP_DIR_NAME))) {
      assertThat(files).isEmpty();
    }
  }
}
