package com.spring.app.modules.upload.services.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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
  void downloadHeaderCarriesNonAsciiFileName() {
    String name = "b\u00e1o c\u00e1o.pdf"; // "báo cáo.pdf", escaped so the source encoding cannot matter
    assumeTrue(fileSystemCanStore(name), "platform file-name encoding cannot represent non-ASCII names");
    UploadServiceImpl service = newService();
    service.storeFile(new MockMultipartFile("file", name, "application/pdf", "%PDF".getBytes(StandardCharsets.UTF_8)));

    String header = service.downFile(name).getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);

    assertThat(header)
      .startsWith("attachment;")
      .contains("filename*=UTF-8''b%C3%A1o%20c%C3%A1o.pdf");
    assertThat(ContentDisposition.parse(header).getFilename()).isEqualTo(name);
  }

  @Test
  void downloadHeaderOfAsciiNameIsAPlainLegacyFilename() {
    UploadServiceImpl service = newService();
    service.storeFile(new MockMultipartFile("file", "report.pdf", "application/pdf",
      "%PDF".getBytes(StandardCharsets.UTF_8)));

    String header = service.downFile("report.pdf").getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);

    assertThat(header).isEqualTo("attachment; filename=\"report.pdf\"");
  }

  @ParameterizedTest
  @ValueSource(strings = {
    "a\"b.txt", "a%b.txt", "a;b.txt", "a\\b.txt", "a\rb.txt", "a\nb.txt", "a\tb.txt", "a\u0001b.txt",
    "a\u007fb.txt", "a\u0085b.txt", "a\u2028b.txt", "a\u2029b.txt",
    "a\u202eb.txt", "a\u200bb.txt", "a\u200fb.txt", "a\ufeffb.txt", "a\ud83d\udc01\udb40\udc01b.txt",
    "a\"b\r\nSet-Cookie: x=y.txt" })
  void rejectsFileNamesThatCouldNeverBeServedOrWouldBreakHeadersAndLogs(String name) throws IOException {
    UploadServiceImpl service = newService();

    assertThatThrownBy(() -> service.storeFile(file(name, "hello")))
      .isInstanceOf(BadRequestException.class)
      .hasMessage("Invalid file name");
    assertThat(dir.resolve(USER_A)).doesNotExist();
    assertTempDirEmpty();
  }

  @Test
  void rejectsFileNameLongerThan255Utf8Bytes() {
    UploadServiceImpl service = newService();
    String longest = "a".repeat(251) + ".txt"; // exactly 255 bytes
    String tooLong = "a".repeat(252) + ".txt";
    String tooLongMultiByte = "\u00e1".repeat(126) + ".txt"; // 130 chars but 2 bytes each: 252 + 4 = 256 bytes

    service.storeFile(file(longest, "hello"));
    assertThat(fileOf(USER_A, longest)).hasContent("hello");
    for (String name : List.of(tooLong, tooLongMultiByte)) {
      assertThat(name.getBytes(StandardCharsets.UTF_8)).hasSize(256);
      assertThatThrownBy(() -> service.storeFile(file(name, "hello")))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("File name is too long");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = { "evil.html/..", "a.png.", "README", "..", "." })
  void rejectsNamesWithoutAnAllowedExtension(String name) {
    UploadServiceImpl service = newService();

    assertThatThrownBy(() -> service.storeFile(file(name, "hello")))
      .isInstanceOf(BadRequestException.class);
    assertThat(dir.resolve(USER_A)).doesNotExist();
  }

  @Test
  void rejectsNameContainingNul() {
    UploadServiceImpl service = newService();

    assertThatThrownBy(() -> service.storeFile(file("a\0.txt", "hello")))
      .isInstanceOf(BadRequestException.class)
      .hasMessage("Invalid file name");
  }

  @Test
  void acceptsDoubleExtensionWhenTheLastOneIsAllowedAndContentTypeMatches() {
    UploadServiceImpl service = newService();

    service.storeFile(new MockMultipartFile("file", "x.html.png", "image/png", new byte[] { 1 }));

    assertThat(fileOf(USER_A, "x.html.png")).exists();
  }

  @Test
  void clientSuppliedDirectoriesInTheOriginalFileNameAreDropped() {
    UploadServiceImpl service = newService();

    BaseResponse<?> body = body(service.storeFile(file("../../x.txt", "hello")));

    assertThat(body.getData()).isEqualTo("x.txt");
    assertThat(fileOf(USER_A, "x.txt")).hasContent("hello");
    assertThat(dir.resolve("x.txt")).doesNotExist();
    assertThat(dir.getParent().resolve("x.txt")).doesNotExist();
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
  void tempFileIsNeverWiderThanOwnerOnlyWhileBeingWritten() throws IOException {
    assumeTrue(isPosix(), "file system has no POSIX permissions");
    UploadServiceImpl service = newService();
    List<Set<PosixFilePermission>> modesSeen = new java.util.ArrayList<>();
    MockMultipartFile upload = new MockMultipartFile("file", "a.txt", "text/plain",
      "hello".getBytes(StandardCharsets.UTF_8)) {
      @Override
      public InputStream getInputStream() throws IOException {
        InputStream delegate = super.getInputStream();
        return new InputStream() {
          @Override
          public int read() throws IOException {
            record();
            return delegate.read();
          }

          @Override
          public int read(byte[] b, int off, int len) throws IOException {
            record();
            return delegate.read(b, off, len);
          }

          private void record() throws IOException {
            try (Stream<Path> temps = Files.list(dir.resolve(UploadServiceImpl.TEMP_DIR_NAME))) {
              for (Path temp : (Iterable<Path>) temps::iterator) {
                modesSeen.add(Files.getPosixFilePermissions(temp));
              }
            }
          }
        };
      }
    };

    service.storeFile(upload);

    assertThat(modesSeen).isNotEmpty();
    assertThat(modesSeen).allSatisfy(mode ->
      assertThat(mode).isSubsetOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
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

  @Test
  void staleTempFilesAreSweptOnStartup() throws IOException {
    Path tempDir = Files.createDirectories(dir.resolve(UploadServiceImpl.TEMP_DIR_NAME));
    Path stale = Files.writeString(tempDir.resolve("upload-1.tmp"), "partial");
    Path inFlight = Files.writeString(tempDir.resolve("upload-2.tmp"), "still writing");
    Path legacy = Files.writeString(dir.resolve(".upload-3.tmp"), "partial");
    Path oldUserFile = Files.writeString(Files.createDirectories(dir.resolve(USER_A)).resolve("old.txt"), "keep");
    makeOlderThanStaleAge(stale);
    makeOlderThanStaleAge(legacy);
    makeOlderThanStaleAge(oldUserFile);

    newService();

    assertThat(stale).doesNotExist();
    assertThat(legacy).doesNotExist();
    assertThat(inFlight).hasContent("still writing");
    assertThat(oldUserFile).hasContent("keep");
  }

  @Test
  void sweepDoesNotFollowSymlinksOrDescendIntoFolders() throws IOException {
    Path tempDir = Files.createDirectories(dir.resolve(UploadServiceImpl.TEMP_DIR_NAME));
    Path outside = Files.writeString(Files.createDirectories(dir.resolve(USER_A)).resolve("keep.txt"), "keep");
    makeOlderThanStaleAge(outside);
    Path nestedDir = Files.createDirectories(tempDir.resolve("nested"));
    Path nested = Files.writeString(nestedDir.resolve("upload-9.tmp"), "nested");
    makeOlderThanStaleAge(nested);
    makeOlderThanStaleAge(nestedDir);
    Path link = tempDir.resolve("upload-link.tmp");
    Path legacyLink = dir.resolve(".upload-link.tmp");
    try {
      Files.createSymbolicLink(link, outside);
      Files.createSymbolicLink(legacyLink, outside);
    } catch (UnsupportedOperationException | IOException ex) {
      assumeTrue(false, "cannot create symbolic links here");
    }

    newService();

    assertThat(outside).hasContent("keep");
    assertThat(nested).hasContent("nested");
    assertThat(Files.isSymbolicLink(link)).isTrue();
    assertThat(Files.isSymbolicLink(legacyLink)).isTrue();
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

  private boolean fileSystemCanStore(String name) {
    try {
      dir.resolve(name);
      return true;
    } catch (InvalidPathException ex) {
      return false;
    }
  }

  private static void makeOlderThanStaleAge(Path file) throws IOException {
    Instant old = Instant.now().minus(UploadServiceImpl.STALE_TEMP_AGE).minusSeconds(60);
    Files.setLastModifiedTime(file, FileTime.from(old));
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
