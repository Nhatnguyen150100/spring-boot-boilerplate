package com.spring.app.modules.upload.services.impl;

import com.spring.app.common.response.ResponseBuilder;
import com.spring.app.configs.properties.FileStorageProperties;
import com.spring.app.exceptions.BadRequestException;
import com.spring.app.modules.upload.services.FileChecksumRegistry;
import com.spring.app.modules.upload.services.UploadServiceInterface;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class UploadServiceImpl implements UploadServiceInterface {

  /** Dot-prefixed so the checksum index never picks up a half-written upload. */
  private static final String TEMP_FILE_PREFIX = ".upload-";

  private final FileStorageProperties fileStorageProperties;
  private final FileChecksumRegistry checksumRegistry;

  private Path uploadPath;

  @PostConstruct
  public void init() throws BadRequestException {
    validateUploadDirConfig();
    initUploadPath();
    indexExistingFiles();
  }

  private void validateUploadDirConfig() throws BadRequestException {
    var uploadDir = fileStorageProperties.getUploadDir();
    if (uploadDir == null || uploadDir.isBlank()) {
      throw new BadRequestException("Upload directory is not configured properly in application properties.");
    }
  }

  private void initUploadPath() throws BadRequestException {
    var uploadDir = fileStorageProperties.getUploadDir();
    this.uploadPath = Paths.get(uploadDir).toAbsolutePath().normalize();
    try {
      Files.createDirectories(uploadPath);
      log.info("Upload folder initialized at: {}", uploadPath);
    } catch (IOException ex) {
      log.error("Error creating upload folder: {}", uploadPath, ex);
      throw new BadRequestException("Cannot create upload folder");
    }
  }

  // Runs in the background so startup does not wait on hashing every stored
  // file; the result is only needed for the log line.
  private void indexExistingFiles() {
    checksumRegistry.indexAsync(uploadPath)
      .thenAccept(count -> log.info("Indexed checksums of {} existing upload(s)", count))
      .exceptionally(ex -> {
        log.error("Could not index existing uploads in {}", uploadPath, ex);
        return null;
      });
  }

  @Override
  public ResponseEntity<?> getUploadPath() {
    if (uploadPath == null) {
      throw new BadRequestException("Upload path is not initialized");
    }
    log.info("Upload path retrieved: {}", uploadPath);
    return ResponseBuilder.success("Get upload path successfully", uploadPath.toString());
  }

  /**
   * Stores the upload unless a stored file already has identical content, in
   * which case nothing is written and the existing file name is returned. The
   * response data is therefore always the name to download the content by.
   */
  @Override
  public ResponseEntity<?> storeFile(MultipartFile file) {
    String fileName = validateFile(file);
    Path targetPath = resolveSafePath(fileName);

    Path tempPath = null;
    try {
      // Written next to the target so the final move is an atomic rename.
      tempPath = Files.createTempFile(uploadPath, TEMP_FILE_PREFIX, ".tmp");
      String checksum = copyAndHash(file, tempPath);

      Path source = tempPath;
      Optional<String> existing = checksumRegistry.storeIfUnique(checksum, fileName,
        () -> Files.move(source, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE));

      if (existing.isPresent()) {
        log.info("Upload {} has the same content as {}; not stored again", fileName, existing.get());
        return ResponseBuilder.success("File with identical content already exists", existing.get());
      }
      log.info("File {} has been saved", fileName);
      return ResponseBuilder.success("File uploaded successfully", fileName);
    } catch (IOException ex) {
      log.error("Error saving file: {}", fileName, ex);
      throw new BadRequestException("Cannot save file");
    } finally {
      deleteTempFile(tempPath);
    }
  }

  // Hashes while copying, so the upload is read exactly once.
  private static String copyAndHash(MultipartFile file, Path target) throws IOException {
    MessageDigest digest = FileChecksumRegistry.newDigest();
    try (InputStream in = new DigestInputStream(file.getInputStream(), digest)) {
      Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
    }
    return FileChecksumRegistry.toHex(digest);
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
  public ResponseEntity<?> downFile(String filename) throws BadRequestException {
    try {
      Path filePath = resolveSafePath(filename);
      Resource resource = new UrlResource(filePath.toUri());

      if (!resource.exists() || !resource.isReadable()) {
        log.warn("File not found: {}", filename);
        throw new BadRequestException("File not found");
      }

      return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + resource.getFilename() + "\"")
        .body(resource);

    } catch (Exception e) {
      log.error("Error downloading file: {}", filename, e);
      throw new BadRequestException("Cannot download file");
    }
  }

  /** @return the bare file name to store the upload under */
  private String validateFile(MultipartFile file) throws BadRequestException {
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

  private Path resolveSafePath(String filename) {
    Path filePath = uploadPath.resolve(filename).normalize();
    if (!filePath.startsWith(uploadPath) || filePath.equals(uploadPath)) {
      throw new BadRequestException("Invalid file path");
    }
    return filePath;
  }

  @Override
  public ResponseEntity<?> deleteFile(String filename) throws BadRequestException {
    try {
      Path filePath = resolveSafePath(filename);
      String storedName = uploadPath.relativize(filePath).toString();
      checksumRegistry.delete(storedName, () -> Files.delete(filePath));
      log.info("File {} has been deleted", filename);
      return ResponseBuilder.success("File deleted successfully", filename);
    } catch (IOException ex) {
      log.error("Error deleting file: {}", filename, ex);
      throw new BadRequestException("Cannot delete file");
    }
  }
}
