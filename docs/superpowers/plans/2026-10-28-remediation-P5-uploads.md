# P5 Uploads Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make file uploads owner-scoped (`<upload-dir>/<userId>/`), always stored under the requested name, written with sane permissions through a swept temp folder, filtered by a configurable type allowlist, and downloaded with a correct `Content-Disposition` and a proper 404.

**Architecture:** `UploadServiceImpl` resolves every file name inside the caller's folder, which it gets from a small `UploadOwnerProvider` (backed by the `SecurityContextHolder` in production, by a lambda in tests). Uploads stream into `<upload-dir>/.tmp/`, get their POSIX mode set, and are atomically renamed into the owner's folder. The in-memory checksum registry (`FileChecksumRegistry`) is **deleted**. See "Dedup decision" below for why.

**Tech Stack:** Java 21, Spring Boot 3.5.x (Spring Framework 6.2 `ContentDisposition`), Spring Security `SecurityContextHolder`, Jakarta Validation, JUnit 5 + AssertJ (`@TempDir`, `Assumptions`), Maven.

**Spec:** `docs/superpowers/specs/2026-10-28-review-remediation-design.md` (Decision 1, Global constraints, file-ownership table, section "### P5 uploads")

## Global Constraints

Copied from the spec. Every task's requirements implicitly include these.

- Java 21, Spring Boot 3.5.x, Maven. `mvn test` must pass with nothing running (no MySQL, no Redis, no SMTP, no Docker).
- Config rules from CLAUDE.md are binding: framework keys under `spring.*`, app keys under `application.*` bound by `configs/properties/*` with `@Validated`; the six leaf properties classes keep `ignoreUnknownFields = false`; every validation-constrained property gets a **literal** override in `src/test/resources/application-test.yml`; every new env var is added to `.env.example`; profile files hold deltas only and never set `spring.profiles.active`.
- Flyway owns the schema; any schema change is a new versioned migration. (P5 makes **no** schema change: "Ownership is enforced by path; no schema change.")
- TDD: each fix lands with a test that fails before and passes after, unless the change is pure config/docs (then state how it was verified).
- Match surrounding code style: 2-space indent, Lombok, constructor injection, `ResponseBuilder` for HTTP responses.
- Commit per task. Commit messages end with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- Decision 1 (binding): "Files live under `<upload-dir>/<userId>/`. Ownership is enforced by path; no schema change. Checksum dedup is scoped to the owner's folder and NEVER substitutes another name: an upload is always stored under the requested name (dedup becomes 'identical content already stored as X' logging/metadata only, or is removed if it no longer earns its keep — plan author decides, documented)."
- P5 owns: `modules/upload/**`, `FileStorageProperties`, `application.yml` (`application.file.storage` section), tests under `modules/upload`. It may append to `application-test.yml` and add test classes in its own area. Adding the new `UPLOAD_*` variables to `.env.example` is required by the global "every new env var is added to `.env.example`" rule (P2 owns only that file's mail/async part).

## Dedup decision: drop the registry

`FileChecksumRegistry` and its test are deleted; uploads are no longer hashed.

Why:
- Decision 1 says an upload is always stored under the requested name. Dedup can therefore no longer save disk space or change the response. The only thing left would be a log line: "identical content already stored as X".
- That log line still needs a checksum index for every user folder. The index would be either (a) the startup re-hash that the spec flags (every file hashed on each boot, unbounded maps), or (b) a lazy per-user scan that re-reads the whole folder on every upload. Both cost real I/O and code (lock, two maps, async indexing on the `taskExecutor` bean that P2 restructures) for a log line nobody acts on.
- Deleting the registry fixes each P5 dedup bullet by construction: no redirect to another name, no cross-user name leak, no stale `a.txt`, and deleting one file can never remove another file's content. It also fixes "startup index re-hashes everything / unbounded maps" and "dot-files skipped by the index".
- If content-addressed storage is wanted later, it belongs in a metadata table with a unique `(owner_id, checksum)` index (a Flyway migration), not in process memory.

Consequence, also documented: concurrent uploads of the same name by the same user are last-rename-wins. Both requests get 200, and the folder holds one complete file, never a mix of the two.

## Operational note (legacy files)

Files that earlier versions stored flat in `<upload-dir>/` have no owner, so after this plan no endpoint serves them. P5 does not migrate them, because nothing records who uploaded them. Operators move them into `<upload-dir>/<userId>/` by hand or delete them. Leftover legacy temp files (`<upload-dir>/.upload-*.tmp`) **are** removed by the startup sweep (Task 6). This goes into the docs via the P6 hand-off.

## Preconditions (run once before Task 1)

P1–P4 have already been applied. Line numbers in this plan are at HEAD `92eac07`. P1–P4 may move lines in `application.yml` / `application-test.yml`, so match those on the quoted content, not the line number.

- [ ] Confirm no earlier plan touched P5's Java files:

```bash
git diff 92eac07 --stat -- src/main/java/com/spring/app/modules/upload src/test/java/com/spring/app/modules/upload src/main/java/com/spring/app/configs/properties/FileStorageProperties.java
```

Expected: no output. If there is output, re-read those files before editing. The edits below assume the HEAD `92eac07` contents.

- [ ] Confirm the JWT principal is still the `User` entity (the owner id comes from it):

```bash
grep -rn "new UsernamePasswordAuthenticationToken(" src/main/java
grep -n "implements UserDetails" src/main/java/com/spring/app/modules/auth/entities/User.java
```

Expected: the JWT filter path (`JwtFunctionUtil` at HEAD, or wherever P3 moved it) builds the token with the `UserDetails` it loaded from `userDetailsService`, and `User` still `implements UserDetails` and extends `BaseEntity` (UUID `id`). If P3 changed the principal to another type that carries the user id, add one more `instanceof` branch for that type in `SecurityContextUploadOwnerProvider.currentOwnerId()` (Task 1) and a matching test.

---

## File Structure

| Path | Action | Responsibility |
|---|---|---|
| `src/main/java/com/spring/app/modules/upload/services/UploadOwnerProvider.java` | Create | Functional interface: "whose folder does this request use" |
| `src/main/java/com/spring/app/modules/upload/services/impl/SecurityContextUploadOwnerProvider.java` | Create | Production impl: user id from `SecurityContextHolder` |
| `src/main/java/com/spring/app/modules/upload/services/impl/UploadServiceImpl.java` | Rewrite (HEAD lines 1-204) | Per-user storage, temp dir, sweep, permissions, allowlist, download headers |
| `src/main/java/com/spring/app/modules/upload/services/UploadServiceInterface.java` | Modify (HEAD lines 1-14) | Drop `getUploadPath`; `downFile` returns `ResponseEntity<Resource>` |
| `src/main/java/com/spring/app/modules/upload/controller/UploadController.java` | Modify (HEAD lines 3-12, 35-40) | Typed download return |
| `src/main/java/com/spring/app/modules/upload/services/FileChecksumRegistry.java` | Delete | Dedup registry (see decision) |
| `src/test/java/com/spring/app/modules/upload/services/FileChecksumRegistryTest.java` | Delete | Its test |
| `src/main/java/com/spring/app/configs/properties/FileStorageProperties.java` | Modify (HEAD lines 1-19) | Allowlists + file permissions, validated |
| `src/main/resources/application.yml` | Modify (HEAD lines 156-158, `application.file.storage`) | New keys + env placeholders |
| `src/test/resources/application-test.yml` | Modify (HEAD lines 60-62) | Upload dir under `target/`, literal overrides |
| `.env.example` | Append after `UPLOAD_DIR` | `UPLOAD_ALLOWED_CONTENT_TYPES`, `UPLOAD_ALLOWED_EXTENSIONS`, `UPLOAD_FILE_PERMISSIONS` |
| `src/test/java/com/spring/app/modules/upload/services/impl/SecurityContextUploadOwnerProviderTest.java` | Create | Owner resolution tests |
| `src/test/java/com/spring/app/modules/upload/services/impl/UploadServiceImplTest.java` | Rewrite (HEAD lines 1-122) | Service behaviour on a real `@TempDir` |
| `src/test/java/com/spring/app/configs/properties/FileStoragePropertiesTest.java` | Create | Constraint + binding tests |

Callers checked: `UploadServiceInterface` is used only by `UploadController`. `FileChecksumRegistry` is used only by `UploadServiceImpl` and its own test. `getUploadPath` is not exposed by any controller, so only the method goes. `FILES_BASE_PATH` (`ApplicationConstants.java:24`) and the upload rate-limit wiring (`WhitelistUrlConstant.java:9`, `RateLimitFilter.java:48,70`) are unchanged.

---

### Task 1: Owner id from the security context

**Files:**
- Create: `src/main/java/com/spring/app/modules/upload/services/UploadOwnerProvider.java`
- Create: `src/main/java/com/spring/app/modules/upload/services/impl/SecurityContextUploadOwnerProvider.java`
- Test: `src/test/java/com/spring/app/modules/upload/services/impl/SecurityContextUploadOwnerProviderTest.java`

**Interfaces:**
- Consumes: `com.spring.app.modules.auth.entities.User` (`implements UserDetails`, `getId(): UUID` from `BaseEntity`); `SecurityContextHolder`.
- Produces: `interface UploadOwnerProvider { String currentOwnerId(); }`, a `@FunctionalInterface`, so tests pass `currentUser::get`. `@Component SecurityContextUploadOwnerProvider implements UploadOwnerProvider` returns `user.getId().toString()`, or throws `org.springframework.security.access.AccessDeniedException("Uploads require an authenticated user")`. The existing `GlobalExceptionHandler` maps that exception to 403.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/spring/app/modules/upload/services/impl/SecurityContextUploadOwnerProviderTest.java`:

```java
package com.spring.app.modules.upload.services.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import com.spring.app.modules.auth.entities.User;

class SecurityContextUploadOwnerProviderTest {

  private final SecurityContextUploadOwnerProvider provider = new SecurityContextUploadOwnerProvider();

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void returnsTheIdOfTheAuthenticatedUser() {
    UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
    User user = User.builder().email("a@example.com").password("x").build();
    user.setId(id);
    authenticate(new UsernamePasswordAuthenticationToken(user, null, List.of()));

    assertThat(provider.currentOwnerId()).isEqualTo(id.toString());
  }

  @Test
  void rejectsMissingAuthentication() {
    assertThatThrownBy(provider::currentOwnerId).isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void rejectsAnonymousCaller() {
    authenticate(new AnonymousAuthenticationToken("key", "anonymousUser",
      AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

    assertThatThrownBy(provider::currentOwnerId).isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void rejectsPrincipalThatIsNotAnApplicationUser() {
    authenticate(new UsernamePasswordAuthenticationToken("someone@example.com", null, List.of()));

    assertThatThrownBy(provider::currentOwnerId).isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void rejectsUserWithoutId() {
    User user = User.builder().email("a@example.com").password("x").build();
    authenticate(new UsernamePasswordAuthenticationToken(user, null, List.of()));

    assertThatThrownBy(provider::currentOwnerId).isInstanceOf(AccessDeniedException.class);
  }

  private static void authenticate(Authentication authentication) {
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=SecurityContextUploadOwnerProviderTest`
Expected: BUILD FAILURE, test compilation error `cannot find symbol ... class SecurityContextUploadOwnerProvider`.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/spring/app/modules/upload/services/UploadOwnerProvider.java`:

```java
package com.spring.app.modules.upload.services;

/**
 * Supplies the id of the user whose upload folder the current request works
 * in. Every upload, download and delete is confined to that folder.
 */
@FunctionalInterface
public interface UploadOwnerProvider {

  /** @return the current user's id, used verbatim as a folder name; never blank */
  String currentOwnerId();
}
```

Create `src/main/java/com/spring/app/modules/upload/services/impl/SecurityContextUploadOwnerProvider.java`:

```java
package com.spring.app.modules.upload.services.impl;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.upload.services.UploadOwnerProvider;

/**
 * Takes the owner from the JWT-authenticated principal, which
 * {@code JwtAuthenticatorFilter} sets to the {@link User} entity. The session
 * policy is STATELESS, so file endpoints never see an OAuth2 session principal.
 */
@Component
public class SecurityContextUploadOwnerProvider implements UploadOwnerProvider {

  @Override
  public String currentOwnerId() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
      && authentication.getPrincipal() instanceof User user
      && user.getId() != null) {
      return user.getId().toString();
    }
    throw new AccessDeniedException("Uploads require an authenticated user");
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=SecurityContextUploadOwnerProviderTest`
Expected: BUILD SUCCESS (no output with `-q`), exit code 0, 5 tests run.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/spring/app/modules/upload/services/UploadOwnerProvider.java \
  src/main/java/com/spring/app/modules/upload/services/impl/SecurityContextUploadOwnerProvider.java \
  src/test/java/com/spring/app/modules/upload/services/impl/SecurityContextUploadOwnerProviderTest.java
git commit -m "$(cat <<'EOF'
feat(upload): resolve upload owner id from the security context

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Per-user folders, temp subfolder, no dedup, 404 for missing files

**Files:**
- Rewrite: `src/main/java/com/spring/app/modules/upload/services/impl/UploadServiceImpl.java` (HEAD lines 1-204)
- Modify: `src/main/java/com/spring/app/modules/upload/services/UploadServiceInterface.java` (HEAD lines 1-14)
- Modify: `src/main/java/com/spring/app/modules/upload/controller/UploadController.java` (HEAD lines 3-12 imports, 35-40 download method)
- Delete: `src/main/java/com/spring/app/modules/upload/services/FileChecksumRegistry.java`
- Delete: `src/test/java/com/spring/app/modules/upload/services/FileChecksumRegistryTest.java`
- Rewrite: `src/test/java/com/spring/app/modules/upload/services/impl/UploadServiceImplTest.java` (HEAD lines 1-122)

**Interfaces:**
- Consumes: `UploadOwnerProvider.currentOwnerId()` (Task 1); `FileStorageProperties.getUploadDir()`; `ResourceNotFoundException(String)` and `BadRequestException(String)` from `com.spring.app.exceptions`.
- Produces:
  - `UploadServiceImpl(FileStorageProperties, UploadOwnerProvider)`, generated by `@RequiredArgsConstructor` in that field order.
  - `static final String UploadServiceImpl.TEMP_DIR_NAME = ".tmp"` (package-private; tests use it).
  - `UploadServiceInterface { ResponseEntity<?> storeFile(MultipartFile); ResponseEntity<Resource> downFile(String); ResponseEntity<?> deleteFile(String); }`
  - Messages: success `"File uploaded successfully"` / `"File deleted successfully"`. Errors: `"File not found"` (ResourceNotFoundException), `"Invalid file path"`, `"File name is missing"`, `"Invalid file name"`, `"File is empty or not provided"`, `"Cannot save file"`, `"Cannot delete file"` (BadRequestException).
  - Disk layout: `<root>/.tmp/upload-*.tmp` (in flight) and `<root>/<ownerId>/<name>`.

- [ ] **Step 1: Write the failing test**

Replace the whole content of `src/test/java/com/spring/app/modules/upload/services/impl/UploadServiceImplTest.java` with:

```java
package com.spring.app.modules.upload.services.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

  private UploadServiceImpl newService() {
    properties.setUploadDir(dir.toString());
    UploadServiceImpl service = new UploadServiceImpl(properties, currentUser::get);
    service.init();
    return service;
  }

  private Path fileOf(String user, String name) {
    return dir.resolve(user).resolve(name);
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=UploadServiceImplTest`
Expected: BUILD FAILURE with test compilation errors. For example, `constructor UploadServiceImpl in class ...UploadServiceImpl cannot be applied to given types` (the current constructor takes a `FileChecksumRegistry`), `cannot find symbol ... TEMP_DIR_NAME`, and `incompatible types: ResponseEntity<capture of ?> cannot be converted to ResponseEntity<Resource>`.

- [ ] **Step 3: Write minimal implementation**

3a. Delete the registry and its test:

```bash
git rm src/main/java/com/spring/app/modules/upload/services/FileChecksumRegistry.java \
  src/test/java/com/spring/app/modules/upload/services/FileChecksumRegistryTest.java
```

3b. Replace the whole content of `src/main/java/com/spring/app/modules/upload/services/UploadServiceInterface.java` with:

```java
package com.spring.app.modules.upload.services;

import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

/** File operations, always scoped to the calling user's own folder. */
public interface UploadServiceInterface {
  ResponseEntity<?> storeFile(MultipartFile file);

  ResponseEntity<Resource> downFile(String filename);

  ResponseEntity<?> deleteFile(String filename);
}
```

(`getUploadPath` is removed: it returned the absolute server path. No controller exposed it.)

3c. In `src/main/java/com/spring/app/modules/upload/controller/UploadController.java`, add `import org.springframework.core.io.Resource;` directly above `import org.springframework.http.*;` (HEAD line 6), and replace HEAD lines 35-40 (the download method) with:

```java
  @Operation(summary = "Download a file by filename", description = "Downloads one of the caller's own files by its filename")
  @GetMapping("/download/{filename:.+}")
  public ResponseEntity<Resource> downloadFileByName(
    @Parameter(description = "Filename to download", required = true) @PathVariable String filename) {
    return uploadService.downFile(filename);
  }
```

3d. Replace the whole content of `src/main/java/com/spring/app/modules/upload/services/impl/UploadServiceImpl.java` with:

```java
package com.spring.app.modules.upload.services.impl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.spring.app.common.response.ResponseBuilder;
import com.spring.app.configs.properties.FileStorageProperties;
import com.spring.app.exceptions.BadRequestException;
import com.spring.app.exceptions.ResourceNotFoundException;
import com.spring.app.modules.upload.services.UploadOwnerProvider;
import com.spring.app.modules.upload.services.UploadServiceInterface;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Stores uploads on the local file system, one folder per user:
 *
 * <pre>
 * &lt;upload-dir&gt;/
 *   .tmp/        in-flight uploads; never reachable through any endpoint
 *   &lt;userId&gt;/    the user's files, under the exact name they were uploaded as
 * </pre>
 *
 * <p>
 * Ownership is enforced by path alone: every file name is resolved inside the
 * caller's folder, so a user can neither read, overwrite nor delete another
 * user's file. An upload is always stored under the requested name; there is
 * no content dedup (it could no longer save space or change the response).
 * </p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class UploadServiceImpl implements UploadServiceInterface {

  /**
   * Folder for in-flight uploads. Dot-prefixed, so it can never equal a user
   * id (a UUID) and is never reachable through a user's folder.
   */
  static final String TEMP_DIR_NAME = ".tmp";

  private final FileStorageProperties fileStorageProperties;
  private final UploadOwnerProvider uploadOwnerProvider;

  private Path uploadRoot;
  private Path tempDir;

  @PostConstruct
  public void init() {
    uploadRoot = Paths.get(fileStorageProperties.getUploadDir()).toAbsolutePath().normalize();
    tempDir = uploadRoot.resolve(TEMP_DIR_NAME);
    try {
      Files.createDirectories(tempDir);
    } catch (IOException ex) {
      throw new IllegalStateException("Cannot create upload folder " + uploadRoot, ex);
    }
    log.info("Upload folder initialized at: {}", uploadRoot);
  }

  @Override
  public ResponseEntity<?> storeFile(MultipartFile file) {
    String fileName = validateFile(file);
    Path ownerDir = ownerDir();
    Path targetPath = resolveInOwnerDir(ownerDir, fileName);

    Path tempPath = null;
    try {
      Files.createDirectories(ownerDir);
      // Same file system as the target, so the final move is an atomic rename.
      tempPath = Files.createTempFile(tempDir, "upload-", ".tmp");
      try (InputStream in = file.getInputStream()) {
        Files.copy(in, tempPath, StandardCopyOption.REPLACE_EXISTING);
      }
      Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      log.info("File {} has been saved for user {}", fileName, ownerDir.getFileName());
      return ResponseBuilder.success("File uploaded successfully", fileName);
    } catch (IOException ex) {
      log.error("Error saving file: {}", fileName, ex);
      throw new BadRequestException("Cannot save file");
    } finally {
      deleteTempFile(tempPath);
    }
  }

  // No-op once the temp file has been moved into place.
  private static void deleteTempFile(Path tempPath) {
    if (tempPath == null) {
      return;
    }
    try {
      Files.deleteIfExists(tempPath);
    } catch (IOException ex) {
      log.warn("Could not delete temp upload file {}", tempPath, ex);
    }
  }

  @Override
  public ResponseEntity<Resource> downFile(String filename) {
    Path filePath = resolveInOwnerDir(ownerDir(), filename);
    if (!Files.isRegularFile(filePath) || !Files.isReadable(filePath)) {
      throw new ResourceNotFoundException("File not found");
    }
    return ResponseEntity.ok()
      .contentType(MediaType.APPLICATION_OCTET_STREAM)
      .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filePath.getFileName() + "\"")
      .body(new FileSystemResource(filePath));
  }

  @Override
  public ResponseEntity<?> deleteFile(String filename) {
    Path filePath = resolveInOwnerDir(ownerDir(), filename);
    try {
      Files.delete(filePath);
    } catch (NoSuchFileException ex) {
      throw new ResourceNotFoundException("File not found");
    } catch (IOException ex) {
      log.error("Error deleting file: {}", filename, ex);
      throw new BadRequestException("Cannot delete file");
    }
    log.info("File {} has been deleted", filename);
    return ResponseBuilder.success("File deleted successfully", filename);
  }

  /** @return the bare file name to store the upload under */
  private String validateFile(MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new BadRequestException("File is empty or not provided");
    }
    String originalName = file.getOriginalFilename();
    if (originalName == null || originalName.isBlank()) {
      throw new BadRequestException("File name is missing");
    }
    try {
      Path name = Path.of(originalName).getFileName();
      if (name == null) {
        throw new BadRequestException("Invalid file name");
      }
      return name.toString();
    } catch (InvalidPathException ex) {
      throw new BadRequestException("Invalid file name");
    }
  }

  /** The caller's folder: a direct child of the upload root, never the temp folder. */
  private Path ownerDir() {
    String ownerId = uploadOwnerProvider.currentOwnerId();
    Path dir = uploadRoot.resolve(ownerId).normalize();
    if (ownerId.isBlank() || ownerId.startsWith(".") || !uploadRoot.equals(dir.getParent())) {
      throw new IllegalStateException("Unusable upload owner id");
    }
    return dir;
  }

  /** Resolves {@code filename} to a direct child of {@code ownerDir}, or rejects it. */
  private static Path resolveInOwnerDir(Path ownerDir, String filename) {
    try {
      Path filePath = ownerDir.resolve(filename).normalize();
      if (!ownerDir.equals(filePath.getParent())) {
        throw new BadRequestException("Invalid file path");
      }
      return filePath;
    } catch (InvalidPathException ex) {
      throw new BadRequestException("Invalid file path");
    }
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q test -Dtest=UploadServiceImplTest`
Expected: BUILD SUCCESS, 11 tests run, 0 failures.

Run: `mvn -q test -Dtest=BaseSpringApplicationTests`
Expected: BUILD SUCCESS. The context wires `UploadServiceImpl` with the single `UploadOwnerProvider` bean (`SecurityContextUploadOwnerProvider`).

Run: `grep -rn "FileChecksumRegistry\|getUploadPath" src`
Expected: no output.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/spring/app/modules/upload src/test/java/com/spring/app/modules/upload
git commit -m "$(cat <<'EOF'
fix(upload): store files per user and always under the requested name

Files now live in <upload-dir>/<userId>/, so users can no longer overwrite,
download or delete each other's files. The in-memory checksum registry is
removed: dedup redirected uploads to other (and other users') file names.
Missing files are a 404, and the endpoint-less getUploadPath is gone.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Upload settings: allowlists, file permissions, test upload dir under `target/`

**Files:**
- Modify: `src/main/java/com/spring/app/configs/properties/FileStorageProperties.java` (HEAD lines 1-19, whole file)
- Modify: `src/main/resources/application.yml` (HEAD lines 156-158, the `application.file.storage` block)
- Modify: `src/test/resources/application-test.yml` (HEAD lines 60-62, the `application.file.storage` block)
- Modify: `.env.example` (after the `UPLOAD_DIR` line)
- Test: `src/test/java/com/spring/app/configs/properties/FileStoragePropertiesTest.java`

**Interfaces:**
- Consumes: none.
- Produces (Lombok `@Data`):
  - `List<String> getAllowedContentTypes()` / `setAllowedContentTypes(List<String>)`, default `[image/png, image/jpeg, image/gif, image/webp, application/pdf, text/plain]`
  - `List<String> getAllowedExtensions()` / `setAllowedExtensions(List<String>)`, default `[png, jpg, jpeg, gif, webp, pdf, txt]`
  - `String getFilePermissions()` / `setFilePermissions(String)`, default `"rw-r--r--"`, pattern `([r-][w-][x-]){3}`
  - Keys: `application.file.storage.{upload-dir, allowed-content-types, allowed-extensions, file-permissions}`. Env vars: `UPLOAD_ALLOWED_CONTENT_TYPES`, `UPLOAD_ALLOWED_EXTENSIONS`, `UPLOAD_FILE_PERMISSIONS`.

The prefix stays `application.file.storage`; the spec's "`application.file-storage`" refers to this block. Renaming it would churn `UPLOAD_DIR` consumers for no gain.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/spring/app/configs/properties/FileStoragePropertiesTest.java`:

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=FileStoragePropertiesTest`
Expected: BUILD FAILURE, test compilation errors `cannot find symbol ... getFilePermissions()` / `setAllowedContentTypes(...)`.

- [ ] **Step 3: Write minimal implementation**

3a. Replace the whole content of `src/main/java/com/spring/app/configs/properties/FileStorageProperties.java` with:

```java
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
```

3b. In `src/main/resources/application.yml`, replace the block (HEAD lines 156-158):

```yaml
  file:
    storage:
      upload-dir: ${UPLOAD_DIR:uploads}
```

with:

```yaml
  file:
    storage:
      # Files live in <upload-dir>/<userId>/; in-flight uploads in <upload-dir>/.tmp/.
      upload-dir: ${UPLOAD_DIR:uploads}
      # An upload is accepted only if BOTH its Content-Type (parameters ignored)
      # and its file-name extension are listed. Comma-separated, case-insensitive.
      allowed-content-types: ${UPLOAD_ALLOWED_CONTENT_TYPES:image/png,image/jpeg,image/gif,image/webp,application/pdf,text/plain}
      allowed-extensions: ${UPLOAD_ALLOWED_EXTENSIONS:png,jpg,jpeg,gif,webp,pdf,txt}
      # Mode of stored files (ls -l form) so e.g. nginx or a backup agent running
      # as another OS user can read them. Ignored on non-POSIX file systems.
      file-permissions: ${UPLOAD_FILE_PERMISSIONS:rw-r--r--}
```

(`application-prod.yml` lines 67-69 override only `upload-dir`, which is a valid delta. Leave it unchanged.)

3c. In `src/test/resources/application-test.yml`, replace the block (HEAD lines 60-62):

```yaml
  file:
    storage:
      upload-dir: uploads-test
```

with:

```yaml
  file:
    storage:
      # Under target/ so `mvn test` never writes into the repository root.
      upload-dir: target/uploads-test
      allowed-content-types: [text/plain, application/pdf, image/png]
      allowed-extensions: [txt, pdf, png]
      file-permissions: rw-r--r--
```

3d. In `.env.example`, find the upload line:

```bash
grep -n '^UPLOAD_DIR' .env.example
```

Insert these lines directly below it. If there is no `UPLOAD_DIR` line, append `UPLOAD_DIR=uploads` followed by these lines at the end of the file, under a `# --- File uploads ---` comment:

```properties
# Uploads are accepted only if BOTH the Content-Type and the extension are listed (comma-separated)
UPLOAD_ALLOWED_CONTENT_TYPES=image/png,image/jpeg,image/gif,image/webp,application/pdf,text/plain
UPLOAD_ALLOWED_EXTENSIONS=png,jpg,jpeg,gif,webp,pdf,txt
# POSIX mode of stored files (ls -l form)
UPLOAD_FILE_PERMISSIONS=rw-r--r--
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q test -Dtest=FileStoragePropertiesTest`
Expected: BUILD SUCCESS, 5 tests run.

Run: `mvn -q test -Dtest=BaseSpringApplicationTests`
Expected: BUILD SUCCESS. Strict binding (`ignoreUnknownFields = false`) accepts the new keys, and the context creates `target/uploads-test/.tmp`.

Run: `test -d target/uploads-test/.tmp && echo ok`
Expected: `ok`

Run: `rmdir uploads-test/.tmp uploads-test 2>/dev/null; test ! -e uploads-test && echo clean`
Expected: `clean`. This removes the stale, untracked folder that earlier test runs left in the repo root. `rmdir` refuses non-empty folders; if it does, inspect the contents before deleting anything.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/spring/app/configs/properties/FileStorageProperties.java \
  src/main/resources/application.yml src/test/resources/application-test.yml .env.example \
  src/test/java/com/spring/app/configs/properties/FileStoragePropertiesTest.java
git commit -m "$(cat <<'EOF'
feat(upload): configurable type allowlist and file permissions

Adds application.file.storage.{allowed-content-types,allowed-extensions,
file-permissions} (validated, literal test overrides, .env.example) and moves
the test upload dir under target/.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Enforce the content-type / extension allowlist

**Files:**
- Modify: `src/main/java/com/spring/app/modules/upload/services/impl/UploadServiceImpl.java` (Task 2 version: imports, fields, `init()`, `validateFile`)
- Test: `src/test/java/com/spring/app/modules/upload/services/impl/UploadServiceImplTest.java`

**Interfaces:**
- Consumes: `FileStorageProperties.getAllowedContentTypes()`, `getAllowedExtensions()` (Task 3). `UploadServiceImplTest.properties`, `newService()`, `currentUser`, `fileOf(...)` (Task 2).
- Produces: `BadRequestException("File type not allowed")` when the Content-Type (`type/subtype`, parameters ignored, case-insensitive; missing or malformed means rejected) or the extension (text after the last `.`, lower-cased; `.env` gives `env`, `README` gives empty) is not allowlisted. Configured extensions may carry a leading dot.

- [ ] **Step 1: Write the failing tests**

In `UploadServiceImplTest.java`, add `import java.util.List;` to the `java.util` imports and add these test methods after `rejectsUploadWithoutFileName()`:

```java
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q test -Dtest=UploadServiceImplTest`
Expected: BUILD FAILURE. `rejectsDisallowedContentType`, `rejectsDisallowedExtension`, `rejectsMissingContentType` and `allowlistIsConfigurable` fail with `Expecting code to raise a throwable` (the file is stored). `acceptsContentTypeParametersAndUpperCaseExtension` passes.

- [ ] **Step 3: Implement**

In `UploadServiceImpl.java`:

Add these imports to the `java.*` group:

```java
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
```

and this one to the `org.springframework.*` group:

```java
import org.springframework.http.InvalidMediaTypeException;
```

Add the fields below `private Path tempDir;`:

```java
  private Set<String> allowedContentTypes;
  private Set<String> allowedExtensions;
```

Replace the whole `init()` method with:

```java
  @PostConstruct
  public void init() {
    uploadRoot = Paths.get(fileStorageProperties.getUploadDir()).toAbsolutePath().normalize();
    tempDir = uploadRoot.resolve(TEMP_DIR_NAME);
    try {
      Files.createDirectories(tempDir);
    } catch (IOException ex) {
      throw new IllegalStateException("Cannot create upload folder " + uploadRoot, ex);
    }
    allowedContentTypes = normalize(fileStorageProperties.getAllowedContentTypes());
    allowedExtensions = normalize(fileStorageProperties.getAllowedExtensions()).stream()
      .map(extension -> extension.startsWith(".") ? extension.substring(1) : extension)
      .collect(Collectors.toUnmodifiableSet());
    log.info("Upload folder initialized at: {}", uploadRoot);
  }

  private static Set<String> normalize(List<String> values) {
    return values.stream()
      .map(value -> value.trim().toLowerCase(Locale.ROOT))
      .collect(Collectors.toUnmodifiableSet());
  }
```

Replace the whole `validateFile` method with:

```java
  /** @return the bare file name to store the upload under */
  private String validateFile(MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new BadRequestException("File is empty or not provided");
    }
    String originalName = file.getOriginalFilename();
    if (originalName == null || originalName.isBlank()) {
      throw new BadRequestException("File name is missing");
    }
    String fileName;
    try {
      Path name = Path.of(originalName).getFileName();
      if (name == null) {
        throw new BadRequestException("Invalid file name");
      }
      fileName = name.toString();
    } catch (InvalidPathException ex) {
      throw new BadRequestException("Invalid file name");
    }
    if (!allowedExtensions.contains(extensionOf(fileName))
      || !allowedContentTypes.contains(baseTypeOf(file.getContentType()))) {
      throw new BadRequestException("File type not allowed");
    }
    return fileName;
  }

  /** @return the text after the last dot, lower-cased; "" when there is none */
  private static String extensionOf(String fileName) {
    int dot = fileName.lastIndexOf('.');
    return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  /** @return "type/subtype" in lower case, or "" when absent or malformed */
  private static String baseTypeOf(String contentType) {
    if (contentType == null || contentType.isBlank()) {
      return "";
    }
    try {
      MediaType type = MediaType.parseMediaType(contentType);
      return (type.getType() + "/" + type.getSubtype()).toLowerCase(Locale.ROOT);
    } catch (InvalidMediaTypeException ex) {
      return "";
    }
  }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q test -Dtest=UploadServiceImplTest`
Expected: BUILD SUCCESS, 16 tests run. The Task 2 tests still pass because they upload `.txt` as `text/plain`, which the defaults allow.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/spring/app/modules/upload/services/impl/UploadServiceImpl.java \
  src/test/java/com/spring/app/modules/upload/services/impl/UploadServiceImplTest.java
git commit -m "$(cat <<'EOF'
fix(upload): reject uploads outside the content-type/extension allowlist

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Explicit POSIX permissions on stored files

**Files:**
- Modify: `src/main/java/com/spring/app/modules/upload/services/impl/UploadServiceImpl.java` (Task 4 version: imports, fields, `init()`, `storeFile`)
- Test: `src/test/java/com/spring/app/modules/upload/services/impl/UploadServiceImplTest.java`

**Interfaces:**
- Consumes: `FileStorageProperties.getFilePermissions()` / `setFilePermissions(String)` (Task 3).
- Produces: a stored file's mode equals `PosixFilePermissions.fromString(filePermissions)` on POSIX file systems. On other file systems nothing is set.

- [ ] **Step 1: Write the failing tests**

In `UploadServiceImplTest.java`, add the imports:

```java
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.FileSystems;
import java.nio.file.attribute.PosixFilePermissions;
```

and these tests:

```java
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
```

and this helper next to `fileOf`:

```java
  private static boolean isPosix() {
    return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
  }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q test -Dtest=UploadServiceImplTest`
Expected on Linux/macOS: BUILD FAILURE. Both new tests fail with `expected: [OWNER_READ, OWNER_WRITE, GROUP_READ, OTHERS_READ]` (or the configured set) `but was: [OWNER_READ, OWNER_WRITE]`, the 0600 mode from `createTempFile`. On Windows the two tests are skipped.

- [ ] **Step 3: Implement**

In `UploadServiceImpl.java`, add the imports to the `java.*` group:

```java
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
```

Add the fields below `private Set<String> allowedExtensions;`:

```java
  private Set<PosixFilePermission> filePermissions;
  private boolean posix;
```

In `init()`, insert these lines directly before `log.info("Upload folder initialized at: {}", uploadRoot);`:

```java
    filePermissions = PosixFilePermissions.fromString(fileStorageProperties.getFilePermissions());
    posix = uploadRoot.getFileSystem().supportedFileAttributeViews().contains("posix");
```

In `storeFile`, replace:

```java
      try (InputStream in = file.getInputStream()) {
        Files.copy(in, tempPath, StandardCopyOption.REPLACE_EXISTING);
      }
      Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
```

with:

```java
      try (InputStream in = file.getInputStream()) {
        Files.copy(in, tempPath, StandardCopyOption.REPLACE_EXISTING);
      }
      if (posix) {
        // createTempFile makes the file 0600 and the rename keeps that mode;
        // nginx or a backup agent running as another OS user could not read it.
        Files.setPosixFilePermissions(tempPath, filePermissions);
      }
      Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q test -Dtest=UploadServiceImplTest`
Expected: BUILD SUCCESS, 18 tests run (2 skipped on non-POSIX).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/spring/app/modules/upload/services/impl/UploadServiceImpl.java \
  src/test/java/com/spring/app/modules/upload/services/impl/UploadServiceImplTest.java
git commit -m "$(cat <<'EOF'
fix(upload): give stored files the configured POSIX permissions

Files.createTempFile creates 0600 files and the atomic move kept that mode.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: Sweep orphaned temp files on startup

**Files:**
- Modify: `src/main/java/com/spring/app/modules/upload/services/impl/UploadServiceImpl.java` (Task 5 version: imports, constants, `init()`, new `sweep` methods)
- Test: `src/test/java/com/spring/app/modules/upload/services/impl/UploadServiceImplTest.java`

**Interfaces:**
- Consumes: `UploadServiceImpl.TEMP_DIR_NAME` (Task 2).
- Produces: `static final Duration UploadServiceImpl.STALE_TEMP_AGE = Duration.ofHours(1)` (package-private). On `init()`, regular files older than `STALE_TEMP_AGE` are deleted from `<root>/.tmp/` (any name) and from `<root>/` itself when they match the legacy `.upload-*.tmp` pattern. Younger files are kept: on a volume shared by several instances they can be another instance's in-flight upload. User folders are never touched.

- [ ] **Step 1: Write the failing test**

In `UploadServiceImplTest.java`, add the imports:

```java
import java.nio.file.attribute.FileTime;
import java.time.Instant;
```

and this test plus helper:

```java
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

  private static void makeOlderThanStaleAge(Path file) throws IOException {
    Instant old = Instant.now().minus(UploadServiceImpl.STALE_TEMP_AGE).minusSeconds(60);
    Files.setLastModifiedTime(file, FileTime.from(old));
  }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=UploadServiceImplTest`
Expected: BUILD FAILURE, test compilation error `cannot find symbol ... STALE_TEMP_AGE`. Once Step 3 adds only the constant, the test fails with `Expecting path ... not to exist` for `upload-1.tmp`.

- [ ] **Step 3: Implement**

In `UploadServiceImpl.java`, add the imports to the `java.*` group:

```java
import java.nio.file.DirectoryStream;
import java.nio.file.LinkOption;
import java.time.Duration;
import java.time.Instant;
```

Below the `TEMP_DIR_NAME` constant add:

```java
  /**
   * At startup, temp files at least this old are leftovers of a crash. Younger
   * ones are kept, because on a shared volume they can be another instance's
   * in-flight upload.
   */
  static final Duration STALE_TEMP_AGE = Duration.ofHours(1);

  /** Temp files written straight into the upload root by earlier versions. */
  private static final String LEGACY_TEMP_GLOB = ".upload-*.tmp";
```

In `init()`, replace the last line `log.info("Upload folder initialized at: {}", uploadRoot);` with:

```java
    int removed = sweepStaleTempFiles();
    log.info("Upload folder initialized at: {} (removed {} stale temp file(s))", uploadRoot, removed);
```

Add these methods directly below `init()`'s helper `normalize(...)`:

```java
  private int sweepStaleTempFiles() {
    Instant cutoff = Instant.now().minus(STALE_TEMP_AGE);
    return sweep(tempDir, "*", cutoff) + sweep(uploadRoot, LEGACY_TEMP_GLOB, cutoff);
  }

  /** Deletes regular files in {@code dir} matching {@code glob} last modified before {@code cutoff}. */
  private static int sweep(Path dir, String glob, Instant cutoff) {
    int removed = 0;
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir, glob)) {
      for (Path entry : entries) {
        if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
          && Files.getLastModifiedTime(entry).toInstant().isBefore(cutoff)
          && Files.deleteIfExists(entry)) {
          removed++;
        }
      }
    } catch (IOException ex) {
      log.warn("Could not sweep stale temp uploads in {}: {}", dir, ex.getMessage());
    }
    return removed;
  }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q test -Dtest=UploadServiceImplTest`
Expected: BUILD SUCCESS, 19 tests run.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/spring/app/modules/upload/services/impl/UploadServiceImpl.java \
  src/test/java/com/spring/app/modules/upload/services/impl/UploadServiceImplTest.java
git commit -m "$(cat <<'EOF'
fix(upload): sweep orphaned temp uploads on startup

In-flight uploads now live in <upload-dir>/.tmp/, outside every user's
folder, and leftovers older than an hour (plus legacy .upload-*.tmp files
in the root) are deleted at startup.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: RFC 6266 `Content-Disposition` on download

**Files:**
- Modify: `src/main/java/com/spring/app/modules/upload/services/impl/UploadServiceImpl.java` (Task 6 version: imports, `downFile`)
- Test: `src/test/java/com/spring/app/modules/upload/services/impl/UploadServiceImplTest.java`

**Interfaces:**
- Consumes: `downFile(String)` (Task 2).
- Produces: the `Content-Disposition` header is `ContentDisposition.attachment().filename(name, UTF_8)`, rendered by Spring 6.2 as `attachment; filename="=?UTF-8?Q?...?="; filename*=UTF-8''<percent-encoded>`. Clients that follow RFC 6266 read `filename*`, so non-ASCII names arrive intact.

- [ ] **Step 1: Write the failing tests**

In `UploadServiceImplTest.java`, add the imports:

```java
import java.nio.file.InvalidPathException;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
```

and these tests plus helper:

```java
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

  private boolean fileSystemCanStore(String name) {
    try {
      dir.resolve(name);
      return true;
    } catch (InvalidPathException ex) {
      return false;
    }
  }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q test -Dtest=UploadServiceImplTest`
Expected: BUILD FAILURE. `downloadHeaderCarriesNonAsciiFileName` fails with `Expecting actual: "attachment; filename="báo cáo.pdf"" to contain: "filename*=UTF-8''b%C3%A1o%20c%C3%A1o.pdf"`. If the platform file-name encoding cannot represent the name (e.g. `LANG=C`), the test is skipped instead. In that case verify the header on a UTF-8 machine.

- [ ] **Step 3: Implement**

In `UploadServiceImpl.java`, add the imports:

```java
import java.nio.charset.StandardCharsets;
```

```java
import org.springframework.http.ContentDisposition;
```

remove the now-unused `import org.springframework.http.HttpHeaders;`, and replace the whole `downFile` method with:

```java
  @Override
  public ResponseEntity<Resource> downFile(String filename) {
    Path filePath = resolveInOwnerDir(ownerDir(), filename);
    if (!Files.isRegularFile(filePath) || !Files.isReadable(filePath)) {
      throw new ResourceNotFoundException("File not found");
    }
    // Encodes non-ASCII names (RFC 5987 filename*) and escapes quotes.
    ContentDisposition disposition = ContentDisposition.attachment()
      .filename(filePath.getFileName().toString(), StandardCharsets.UTF_8)
      .build();
    return ResponseEntity.ok()
      .contentType(MediaType.APPLICATION_OCTET_STREAM)
      .headers(headers -> headers.setContentDisposition(disposition))
      .body(new FileSystemResource(filePath));
  }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q test -Dtest=UploadServiceImplTest`
Expected: BUILD SUCCESS, 20 tests run.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/spring/app/modules/upload/services/impl/UploadServiceImpl.java \
  src/test/java/com/spring/app/modules/upload/services/impl/UploadServiceImplTest.java
git commit -m "$(cat <<'EOF'
fix(upload): build Content-Disposition with ContentDisposition (UTF-8)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: Full-suite verification

**Files:** none modified (verification only).

**Interfaces:**
- Consumes: everything above.
- Produces: green `mvn test`, nothing written outside `target/`.

- [ ] **Step 1: Run the whole suite with nothing running**

Run: `mvn -q test`
Expected: BUILD SUCCESS, exit code 0.

- [ ] **Step 2: Confirm tests wrote nothing into the repository root**

Run: `test ! -e uploads-test && test -d target/uploads-test/.tmp && echo ok`
Expected: `ok`

Run: `git status --porcelain`
Expected: no untracked `uploads-test/` and no modified files. (Other plans' leftovers, such as `logs/` before P6, are out of scope here.)

- [ ] **Step 3: Confirm no remnants of removed code**

Run: `grep -rn "FileChecksumRegistry\|getUploadPath" src/main src/test`
Expected: no output.

- [ ] **Step 4: Spec self-check (tick each)**

| Spec bullet (P5) | Where |
|---|---|
| Ownership: `<upload-dir>/<userId>/`, id from SecurityContext, download/delete scoped, path safety inside user folder | Tasks 1, 2 (`ownerDir`, `resolveInOwnerDir`; tests `sameFileNameFromTwoUsersDoesNotCollide`, `userCannotDownload/DeleteAnotherUsersFile`, `pathsEscapingTheCallersFolderAreRejected`) |
| Dedup never substitutes a name / no cross-user leak / delete doesn't take another file's content | Task 2 (registry deleted; `reuploadWithContentOfAnotherFileIsStoredUnderTheRequestedName`, `deletingOneOfTwoIdenticalFilesKeepsTheOther`) |
| Permissions (0644 / configurable, POSIX) | Tasks 3, 5 |
| Orphaned temp files: dedicated subfolder outside servable namespace, startup sweep | Tasks 2 (`.tmp/`), 6 |
| User dot-files skipped by index | Gone with the registry (Task 2); dot-files are now subject only to the allowlist (Task 4) |
| Startup index re-hash / unbounded maps | Registry dropped (Dedup decision) |
| Content-Disposition via `ContentDisposition` UTF-8 | Task 7 |
| `catch (Exception)` → 404 `ResourceNotFoundException`, no stack trace | Task 2 (download and delete) |
| Content-type / extension allowlist (validated, test override, `.env.example`) | Tasks 3, 4 |
| Remove `getUploadPath` | Task 2 |
| Upload rate limit per IP vs docs | P6 (hand-off) |
| `contextLoads` writes `uploads-test/` in repo root | Task 3 (`target/uploads-test`) |

---

## Hand-offs

- **P6 (docs: `CLAUDE.md`, `README.md`):** update "File Upload System". Files are stored per user in `<upload-dir>/<userId>/`. In-flight uploads go to `<upload-dir>/.tmp/` and are swept at startup (older than 1 h). Uploads are always stored under the requested name with no dedup, and a re-upload of the same name overwrites. Allowlist via `UPLOAD_ALLOWED_CONTENT_TYPES` / `UPLOAD_ALLOWED_EXTENSIONS`; file mode via `UPLOAD_FILE_PERMISSIONS` (default `rw-r--r--`). Rate limit is per IP (already in the spec). Add the legacy-files operator note: pre-P5 files in the flat `<upload-dir>/` are no longer served; move them into a user folder or delete them.
- **P6 (`Dockerfile`, non-root user):** the runtime user must own or be able to write `UPLOAD_DIR` (`/app/uploads` in both compose files). `.tmp/` is created inside it, so no extra volume is needed, and it must stay on the same volume for the atomic rename.
- **P6 (`.gitignore`):** tests no longer create `uploads-test/` in the root (now `target/uploads-test`), so adding it to `.gitignore` is optional.
- **P6 (dead code) / P2 (`AsyncConfig`):** uploads no longer use the `taskExecutor` bean (`FileChecksumRegistry` was its only explicit `@Qualifier("taskExecutor")` consumer). Keep or remove it based on the remaining users (e.g. `@Async` default executor).
- **P6 (`ApplicationConstants`):** `FILES_BASE_PATH` is still used by `UploadController`; do not remove it.
- **P4 (`GlobalExceptionHandler`):** the rewritten handler must keep `ResourceNotFoundException` → 404, `BadRequestException` → 400 and `AccessDeniedException` → 403 (thrown by `SecurityContextUploadOwnerProvider`), logging 4xx without a stack trace. `MaxUploadSizeExceededException` → 413 is already in P4's scope.
- **P3 (principal):** P5 assumes the JWT-authenticated principal is `modules.auth.entities.User` with a non-null UUID id (as at HEAD). If P3 changed that, the Preconditions check catches it and Task 1 gets one extra `instanceof` branch.
