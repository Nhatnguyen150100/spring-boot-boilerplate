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
