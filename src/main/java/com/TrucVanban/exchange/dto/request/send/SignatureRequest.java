package com.TrucVanban.exchange.dto.request.send;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignatureRequest {

    @NotNull(message = "signatureOrder không được để trống")
    @Min(value = 1, message = "signatureOrder phải >= 1")
    private Integer signatureOrder;

    @NotBlank(message = "signerCode không được để trống")
    private String signerCode;

    @NotBlank(message = "signerRole không được để trống")
    private String signerRole;      // INITIATOR | REVIEWER | FINAL_APPROVER

    @NotBlank(message = "signatureType không được để trống")
    private String signatureType;   // INITIAL | OFFICIAL | STAMP

    @NotBlank(message = "certificateSerialNumber không được để trống")
    private String certificateSerialNumber;

    @NotBlank(message = "timestamp không được để trống")
    private String timestamp;       // ISO 8601

    @NotBlank(message = "signatureValue không được để trống")
    private String signatureValue;  // Base64 PKCS#7 CMS blob từ PDF
}
