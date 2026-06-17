package com.TrucVanban.exchange.dto.response;

import com.TrucVanban.exchange.enums.TransactionStatus;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SenderDocumentResponse {
    private String transactionCode;
    private TransactionStatus currentStatus;
}
