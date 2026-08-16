package com.TrucVanban.exchange.dto.response;

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
}
