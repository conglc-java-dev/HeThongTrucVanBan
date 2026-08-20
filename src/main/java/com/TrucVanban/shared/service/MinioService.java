package com.TrucVanban.shared.service;

import io.minio.*;
import io.minio.errors.MinioException;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MinioService {

    private final MinioClient minioClient;

    @Value("${minio.bucket-name}")
    private String bucketName;

    public String upload(MultipartFile file) {
        try {
            ensureBucketExists();
            String objectName = UUID.randomUUID() + "_" + file.getOriginalFilename();
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectName)
                    .stream(file.getInputStream(), file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build());
            log.info("Upload file thành công: {}", objectName);
            return objectName;
        } catch (MinioException | IOException | InvalidKeyException | NoSuchAlgorithmException e) {
            throw new RuntimeException("Upload file thất bại: " + e.getMessage(), e);
        }
    }

    /**
     * Upload nội dung file từ byte array lên MinIO.
     * Dùng cho VisualSignatureService sau khi PDFBox vẽ dấu xong
     * và cần lưu file PDF mới (đã có dấu) lên MinIO.
     *
     * @param objectName  Object key muốn lưu (bao gồm cả path prefix nếu có)
     * @param data        Nội dung file dạng byte array
     * @param contentType MIME type (vd: "application/pdf")
     * @return objectName đã lưu thành công
     */
    public String uploadBytes(String objectName, byte[] data, String contentType) {
        try {
            ensureBucketExists();
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectName)
                    .stream(new ByteArrayInputStream(data), data.length, -1)
                    .contentType(contentType)
                    .build());
            log.info("Upload bytes thành công: {} ({} bytes)", objectName, data.length);
            return objectName;
        } catch (MinioException | IOException | InvalidKeyException | NoSuchAlgorithmException e) {
            throw new RuntimeException("Upload bytes thất bại: " + e.getMessage(), e);
        }
    }

    public String getPresignedUrl(String objectName) {
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .bucket(bucketName)
                    .object(objectName)
                    .method(Method.GET)
                    .expiry(60 * 60) // 1 hour
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("Không thể lấy URL file: " + e.getMessage(), e);
        }
    }

    /**
     * Tải file từ MinIO theo object key và trả về InputStream.
     * Dùng cho Gateway khi cần parse PDF để xác minh chữ ký.
     * Caller có trách nhiệm đóng InputStream sau khi dùng xong.
     */
    public InputStream download(String objectKey) {
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectKey)
                    .build());
        } catch (MinioException | IOException | InvalidKeyException | NoSuchAlgorithmException e) {
            throw new RuntimeException("Tải file từ MinIO thất bại: objectKey=" + objectKey + " | " + e.getMessage(), e);
        }
    }

    public void deleteByUrl(String url) {
        try {
            String path = new java.net.URI(url).getPath();
            String objectName = path.substring(path.indexOf(bucketName) + bucketName.length() + 1);
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectName)
                    .build());
            log.info("Xóa file thành công: {}", objectName);
        } catch (MinioException | IOException | InvalidKeyException | NoSuchAlgorithmException e) {
            throw new RuntimeException("Xóa file thất bại: " + e.getMessage(), e);
        } catch (java.net.URISyntaxException e) {
            throw new RuntimeException("URL không hợp lệ: " + e.getMessage(), e);
        }
    }

    private void ensureBucketExists() throws MinioException, IOException, InvalidKeyException, NoSuchAlgorithmException {
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
            log.info("Tạo bucket mới: {}", bucketName);
        }
    }
}

