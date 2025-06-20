package com.spring.app.modules.upload.services.impl;

import com.spring.app.common.response.BaseResponse;
import com.spring.app.modules.upload.services.UploadServiceInterface;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.coyote.BadRequestException;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${file.upload-dir}")
    private String uploadDir;

    private Path uploadPath;

    @PostConstruct
    public void init() throws BadRequestException {
        validateUploadDirConfig();
        initUploadPath();
    }

    private void validateUploadDirConfig() throws BadRequestException {
        if (uploadDir == null || uploadDir.isBlank()) {
            throw new BadRequestException("Cấu hình 'file.upload-dir' không được để trống!");
        }
    }

    private void initUploadPath() throws BadRequestException {
        this.uploadPath = Paths.get(uploadDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(uploadPath);
            log.info("Upload folder initialized at: {}", uploadPath);
        } catch (IOException ex) {
            log.error("Không thể tạo thư mục upload tại: {}", uploadPath, ex);
            throw new BadRequestException("Không thể tạo thư mục upload!", ex);
        }
    }

    @Override
    public ResponseEntity<BaseResponse> getUploadPath() {
        return ResponseEntity.ok(BaseResponse.success("Lấy đường dẫn upload thành công", uploadPath.toString()));
    }

    @Override
    public ResponseEntity<BaseResponse> storeFile(MultipartFile file) throws BadRequestException {
        validateFile(file);

        String fileName = Path.of(file.getOriginalFilename()).getFileName().toString();
        Path targetPath = uploadPath.resolve(fileName);

        try {
            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("File đã được lưu: {}", fileName);
            return ResponseEntity.ok(BaseResponse.success("Upload file thành công", fileName));
        } catch (IOException ex) {
            log.error("Lỗi khi lưu file: {}", fileName, ex);
            throw new BadRequestException("Không thể lưu file!", ex);
        }
    }

    @Override
    public ResponseEntity<Resource> downFile(String filename) throws BadRequestException {
        try {
            Path filePath = uploadPath.resolve(filename).normalize();
            Resource resource = new UrlResource(filePath.toUri());

            if (!resource.exists() || !resource.isReadable()) {
                log.warn("Không tìm thấy file: {}", filename);
                throw new BadRequestException("File không tồn tại: " + filename);
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + resource.getFilename() + "\"")
                    .body(resource);

        } catch (Exception e) {
            log.error("Lỗi khi tải file: {}", filename, e);
            throw new BadRequestException("Không thể tải file: " + filename, e);
        }
    }

    private void validateFile(MultipartFile file) throws BadRequestException {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("File tải lên rỗng!");
        }
    }
}
