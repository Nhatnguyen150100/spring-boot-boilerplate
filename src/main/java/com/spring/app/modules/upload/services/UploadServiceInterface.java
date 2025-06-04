package com.spring.app.modules.upload.services;

import org.apache.coyote.BadRequestException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import com.spring.app.common.response.BaseResponse;

public interface UploadServiceInterface {
  ResponseEntity<BaseResponse> getUploadPath();

  ResponseEntity<BaseResponse> storeFile(MultipartFile file) throws BadRequestException;

  ResponseEntity downFile(String filename) throws BadRequestException;
}
