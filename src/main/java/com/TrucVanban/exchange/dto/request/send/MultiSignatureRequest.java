package com.TrucVanban.exchange.dto.request.send;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class MultiSignatureRequest {

    @NotBlank(message = "masterTransactionCode không được để trống")
    private String masterTransactionCode;

    @NotBlank(message = "documentCode không được để trống")
    private String documentCode;

    @NotBlank(message = "currentSenderCode không được để trống")
    private String currentSenderCode;

    private List<String> routingList;

    private List<String> distributionList;

    @NotBlank(message = "storagePath không được để trống")
    private String storagePath;

    @NotBlank(message = "requestTimestamp không được để trống")
    private String requestTimestamp;

    /**
     * Chữ ký RSA lên Canonical String của toàn bộ metadata gói tin.
     */
    @NotBlank(message = "transportSignature không được để trống")
    private String transportSignature;

    @NotEmpty(message = "signatures không được để trống")
    @Valid
    private List<SignatureRequest> signatures;

    private String title;
    private String documentType;
    private Integer priority;
    private JsonNode extractedMetadata;
    private String summary;
}
