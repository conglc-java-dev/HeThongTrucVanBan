package com.TrucVanban.exchange.dto.request;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UpdateDocumentRequest {
    @NotBlank(message = "Mã tổ chức cập nhật là bắt buộc")
    private String requesterCode;
    private String title;
    private String summary;
    private String documentType;
    private JsonNode extractedMetadata;
    private String storagePath;
    private String payloadChecksum;
    private String updateReason;
}
