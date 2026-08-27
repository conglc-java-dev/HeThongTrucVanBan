package com.TrucVanban.exchange.dto.request.send;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.List;

@Data
public class ExchangeDocumentRequest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @NotBlank(message = "Mã tổ chức gửi (senderCode) là bắt buộc")
    private String senderCode;

    @NotEmpty(message = "Danh sách mã tổ chức nhận (receiverCodes) là bắt buộc")
    private List<String> receiverCodes;

    @NotBlank(message = "Mã văn bản (documentCode) là bắt buộc")
    private String documentCode;

    @NotBlank(message = "Mã SHA-256 của file (payloadChecksum) là bắt buộc. Client tự tính sau khi upload lên S3/MinIO.")
    private String payloadChecksum;

    @NotBlank(message = "Số serial chứng thư (certificateSerialNumber) là bắt buộc")
    private String certificateSerialNumber;

    @NotBlank(message = "Thời gian gửi gói tin (timestamp) là bắt buộc. Định dạng ISO 8601.")
    private String timestamp;

    

    @NotBlank(message = "Chữ ký số (signature) là bắt buộc. Định dạng Base64 SHA256withRSA.")
    private String signature;

    @NotBlank(message = "Đường dẫn lưu trữ file (storagePath) là bắt buộc")
    private String storagePath;

    private String replacedDocumentCode;

    private String title;
    private String documentType;
    private Integer priority;
    private JsonNode extractedMetadata;
    private String summary;

    @Pattern(
            regexp = "^(?:\\d{2}-\\d{2}-\\d{4}|\\d{4}-\\d{2}-\\d{2}|\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2}))$",
            message = "Ngày phát hành (issuedDate) phải theo dd-MM-yyyy, ISO 8601 hoặc YYYY-MM-DD"
    )
    private String issuedDate;

    @SuppressWarnings("unused")
    public void setExtractedMetadata(String extractedMetadataStr) throws JsonProcessingException {
        if (extractedMetadataStr != null && !extractedMetadataStr.isBlank()) {
            this.extractedMetadata = MAPPER.readTree(extractedMetadataStr);
        } else {
            this.extractedMetadata = null;
        }
    }
    @SuppressWarnings("unused")
    public void setExtractedMetadata(JsonNode extractedMetadata) {
        this.extractedMetadata = extractedMetadata;
    }
}
