package com.spring.app.modules.upload.services;

import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

public interface UploadServiceInterface {
  ResponseEntity<?> getUploadPath();

  ResponseEntity<?> storeFile(MultipartFile file);

  ResponseEntity<?> downFile(String filename);

  ResponseEntity<?> deleteFile(String filename);
}
