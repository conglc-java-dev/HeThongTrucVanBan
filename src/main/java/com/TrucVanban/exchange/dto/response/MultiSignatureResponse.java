package com.TrucVanban.exchange.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MultiSignatureResponse {

    private Long transactionId;
    private String masterTransactionCode;

    private String signingFlowStatus;

    private Integer currentStep;

    private String nextReceiver;

    private Integer verifiedSignaturesCount;

    // ---- Metadata tuỳ chọn (mirror từ request, tiện client hiển thị lại) ----
    private String title;
    private String documentType;
    private Integer priority;
    private JsonNode extractedMetadata;
    private String summary;
}
