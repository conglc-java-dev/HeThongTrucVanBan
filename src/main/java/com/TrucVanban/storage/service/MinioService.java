package com.TrucVanban.storage.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

public interface MinioService {
    String upload(MultipartFile file);

    String uploadBytes(String objectName, byte[] data, String contentType);

    String getPresignedUrl(String objectName);

    byte[] download(String objectName);

    InputStream downloadAsStream(String objectName);

    void deleteByUrl(String url);
}
