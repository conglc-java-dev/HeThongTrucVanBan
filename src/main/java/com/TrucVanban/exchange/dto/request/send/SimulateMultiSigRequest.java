package com.TrucVanban.exchange.dto.request.send;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
}
