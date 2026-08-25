package com.TrucVanban.exchange.dto.request.send;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.List;

@Data
public class SimulateMultiSigRequest {

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

    @NotBlank(message = "certificateSerialNumber không được để trống")
    private String certificateSerialNumber;

    @NotBlank(message = "signerRole không được để trống (INITIATOR/REVIEWER/FINAL_APPROVER)")
    private String signerRole;

    private String signatureType = "OFFICIAL";

    @Valid
    private List<SignatureRequest> existingSignatures;

    // ---- Metadata tuỳ chọn (đồng bộ với MultiSignatureRequest) ----
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

    /** Tọa độ vẽ con dấu đỏ lên PDF (tùy chọn). */
    @Valid
    private VisualSignatureRequest stampCoords;

    /** Tọa độ vẽ chữ ký tay lên PDF (tùy chọn). */
    @Valid
    private VisualSignatureRequest signatureCoords;
}

