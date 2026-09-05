package com.TrucVanban.exchange.dto.response;

import com.TrucVanban.exchange.dto.request.send.SignatureRequest;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PendingMultiSignatureResponse {
    private String masterTransactionCode;
    private String documentCode;
    private String title;
    private String summary;
    private String documentType;
    private String issuedDate;
    private JsonNode extractedMetadata;
    private String storagePath;
    private Integer priority;
    private Integer currentStep;
    private String currentReceiverCode;
    private List<String> routingList;
    private List<String> distributionList;
    private List<SignatureRequest> existingSignatures;
}