package com.spring.app.modules.upload.services.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.InvalidMediaTypeException;
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

  /**
   * At startup, temp files at least this old are leftovers of a crash. Younger
   * ones are kept, because on a shared volume they can be another instance's
   * in-flight upload.
   */
  static final Duration STALE_TEMP_AGE = Duration.ofHours(1);

  /** Temp files written straight into the upload root by earlier versions. */
  private static final String LEGACY_TEMP_GLOB = ".upload-*.tmp";

  private final FileStorageProperties fileStorageProperties;
  private final UploadOwnerProvider uploadOwnerProvider;

  private Path uploadRoot;
  private Path tempDir;
  private Set<String> allowedContentTypes;
  private Set<String> allowedExtensions;
  private Set<PosixFilePermission> filePermissions;
  private boolean posix;

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
    filePermissions = PosixFilePermissions.fromString(fileStorageProperties.getFilePermissions());
    posix = uploadRoot.getFileSystem().supportedFileAttributeViews().contains("posix");
    int removed = sweepStaleTempFiles();
    log.info("Upload folder initialized at: {} (removed {} stale temp file(s))", uploadRoot, removed);
  }

  private static Set<String> normalize(List<String> values) {
    return values.stream()
      .map(value -> value.trim().toLowerCase(Locale.ROOT))
      .collect(Collectors.toUnmodifiableSet());
  }

  private int sweepStaleTempFiles() {
    Instant cutoff = Instant.now().minus(STALE_TEMP_AGE);
    return sweep(tempDir, "*", cutoff) + sweep(uploadRoot, LEGACY_TEMP_GLOB, cutoff);
  }

  /**
   * Deletes regular files directly in {@code dir} matching {@code glob} last
   * modified before {@code cutoff}. Never follows symbolic links and never
   * descends into sub-folders. Failures are logged, not thrown: a missing
   * folder or a file that vanishes mid-sweep must not stop the application
   * from starting.
   */
  private static int sweep(Path dir, String glob, Instant cutoff) {
    int removed = 0;
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir, glob)) {
      for (Path entry : entries) {
        try {
          if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
            && Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff)
            && Files.deleteIfExists(entry)) {
            removed++;
          }
        } catch (IOException ex) {
          log.warn("Could not sweep stale temp upload {}: {}", entry, ex.getMessage());
        }
      }
    } catch (IOException | RuntimeException ex) {
      // DirectoryIteratorException (a RuntimeException) can surface mid-iteration.
      log.warn("Could not sweep stale temp uploads in {}: {}", dir, ex.getMessage());
    }
    return removed;
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
      // Write into the existing temp file instead of replacing it: createTempFile
      // makes it owner-only (0600), and Files.copy(REPLACE_EXISTING) would recreate
      // it with the umask mode while the upload is still partial.
      try (InputStream in = file.getInputStream();
        OutputStream out = Files.newOutputStream(tempPath, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
        in.transferTo(out);
      }
      applyFilePermissions(tempPath);
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

  /**
   * The temp file is owner-only, and the rename keeps that mode; nginx or a
   * backup agent running as another OS user could not read the stored file.
   * Some mounts report POSIX support but reject chmod: keep the upload working.
   */
  private void applyFilePermissions(Path path) {
    if (!posix) {
      return;
    }
    try {
      Files.setPosixFilePermissions(path, filePermissions);
    } catch (IOException | UnsupportedOperationException ex) {
      log.warn("Could not set permissions on uploaded file, keeping the default mode: {}", ex.getMessage());
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
    // Non-ASCII names, quotes and control characters are percent-encoded in
    // filename* (RFC 5987), so the header cannot be split.
    ContentDisposition disposition = ContentDisposition.attachment()
      .filename(filePath.getFileName().toString(), StandardCharsets.UTF_8)
      .build();
    return ResponseEntity.ok()
      .contentType(MediaType.APPLICATION_OCTET_STREAM)
      .headers(headers -> headers.setContentDisposition(disposition))
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
