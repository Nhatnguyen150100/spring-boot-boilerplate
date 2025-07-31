package com.spring.app.modules.upload.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.spring.app.modules.upload.services.UploadServiceInterface;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@Slf4j
@RestController
@RequestMapping("/files")
@RequiredArgsConstructor
@Validated
@Tag(name = "Upload", description = "APIs for uploading and downloading files")
public class UploadController {

  private final UploadServiceInterface uploadService;

  @Operation(summary = "Upload a single file", description = "Uploads a file and returns its metadata or success message")
  @PostMapping(value = "", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<?> uploadSingleFile(
      @Parameter(description = "File to upload", required = true) @RequestParam("file") MultipartFile file) {
    return uploadService.storeFile(file);
  }

  @Operation(summary = "Download a file by filename", description = "Downloads a file based on its filename")
  @GetMapping("/download/{filename:.+}")
  public ResponseEntity<?> downloadFileByName(
      @Parameter(description = "Filename to download", required = true) @PathVariable String filename) {
    return uploadService.downFile(filename);
  }

  @Operation(summary = "Delete a file by filename", description = "Delete a file based on its filename")
  @DeleteMapping("/delete/{filename:.+}")
  public ResponseEntity<?> deleteFileByName(
      @Parameter(description = "Filename to delete", required = true) @PathVariable String filename) {
    return uploadService.deleteFile(filename);
  }
}
