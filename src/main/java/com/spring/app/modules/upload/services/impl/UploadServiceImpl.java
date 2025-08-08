package com.spring.app.modules.upload.services.impl;

import com.spring.app.common.response.ResponseBuilder;
import com.spring.app.configs.properties.FileStorageProperties;
import com.spring.app.exceptions.BadRequestException;
import com.spring.app.modules.upload.services.UploadServiceInterface;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;

@Service
@Slf4j
public class UploadServiceImpl implements UploadServiceInterface {

  @Autowired
  private FileStorageProperties fileStorageProperties;

  private Path uploadPath;

  @PostConstruct
  public void init() throws BadRequestException {
    validateUploadDirConfig();
    initUploadPath();
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

  @Override
  public ResponseEntity<?> getUploadPath() {
    if (uploadPath == null) {
      throw new BadRequestException("Upload path is not initialized");
    }
    log.info("Upload path retrieved: {}", uploadPath);
    return ResponseBuilder.success("Get upload path successfully", uploadPath.toString());
  }

  @Override
  public ResponseEntity<?> storeFile(MultipartFile file) {
    validateFile(file);

    String fileName = Path.of(file.getOriginalFilename()).getFileName().toString();
    Path targetPath = uploadPath.resolve(fileName);

    try {
      Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);
      log.info("File {} has been saved", fileName);
      return ResponseBuilder.success("File uploaded successfully", fileName);
    } catch (IOException ex) {
      log.error("Error saving file: {}", fileName, ex);
      throw new BadRequestException("Cannot save file");
    }
  }

  @Override
  public ResponseEntity<?> downFile(String filename) throws BadRequestException {
    try {
      Path filePath = uploadPath.resolve(filename).normalize();
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

  private void validateFile(MultipartFile file) throws BadRequestException {
    if (file == null || file.isEmpty()) {
      throw new BadRequestException("File is empty or not provided");
    }
  }

  @Override
  public ResponseEntity<?> deleteFile(String filename) throws BadRequestException {
    try {
      Path filePath = uploadPath.resolve(filename).normalize();
      Files.delete(filePath);
      log.info("File {} has been deleted", filename);
      return ResponseBuilder.success("File deleted successfully", filename);
    } catch (IOException ex) {
      log.error("Error deleting file: {}", filename, ex);
      throw new BadRequestException("Cannot delete file");
    }
  }
}
