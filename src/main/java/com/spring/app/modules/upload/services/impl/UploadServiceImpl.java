package com.spring.app.modules.upload.services.impl;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import org.apache.coyote.BadRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.spring.app.common.response.BaseResponse;
import com.spring.app.modules.upload.services.UploadServiceInterface;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class UploadServiceImpl implements UploadServiceInterface {

  @Value("${file.upload-dir}")
  private String uploadDir;

  private Path uploadPath;

  @PostConstruct
  public void init() throws BadRequestException {
    if (this.uploadDir == null || this.uploadDir.isBlank()) {
      throw new BadRequestException("Configuration file.upload-dir must not be empty!");
    }

    this.uploadPath = Paths.get(this.uploadDir).toAbsolutePath().normalize();

    try {
      Files.createDirectories(this.uploadPath);
      log.info("Upload folder created at: {}", this.uploadPath);
    } catch (IOException ex) {
      log.error("Failed to create upload directory at: {}", this.uploadPath, ex);
      throw new BadRequestException("Could not create upload directory at: " + this.uploadPath, ex);
    }
  }

  @Override
  public ResponseEntity<BaseResponse> getUploadPath() {
    return ResponseEntity.ok(BaseResponse.success("Get upload path successfully", uploadPath));
  }

  @Override
  public ResponseEntity<BaseResponse> storeFile(MultipartFile file) throws BadRequestException {
    log.info(file.toString());
    try {
      if (file.isEmpty())
        throw new BadRequestException("Empty file!");

      String fileName = Path.of(file.getOriginalFilename()).getFileName().toString();
      Path targetLocation = this.uploadPath.resolve(fileName);
      Files.copy(file.getInputStream(), targetLocation, StandardCopyOption.REPLACE_EXISTING);

      log.info("File saved: {}", fileName);
      return ResponseEntity.ok(BaseResponse.success("Upload file successfully", fileName));
    } catch (IOException ex) {
      log.error("Error while saving file", ex);
      throw new BadRequestException("Failed to save file!", ex);
    }
  }

  @Override
  public ResponseEntity downFile(String filename) throws BadRequestException {
    try {
      Path filePath = uploadPath.resolve(filename).normalize();
      Resource resource = new UrlResource(filePath.toUri());

      if (!resource.exists()) {
        log.warn("File not found: {}", filename);
        throw new BadRequestException("File not found: " + filename);
      }

      return ResponseEntity.ok()
          .contentType(MediaType.APPLICATION_OCTET_STREAM)
          .header(HttpHeaders.CONTENT_DISPOSITION,
              "attachment; filename=\"" + resource.getFilename() + "\"")
          .body(resource);
    } catch (Exception e) {
      log.error("Failed to download file: {}", filename, e);
      throw new BadRequestException("Failed to download file: " + filename, e);
    }
  }

}
