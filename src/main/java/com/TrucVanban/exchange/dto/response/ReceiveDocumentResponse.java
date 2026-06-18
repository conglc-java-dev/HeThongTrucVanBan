package com.TrucVanban.exchange.dto.response;

import lombok.Data;

@Data
public class ReceiveDocumentResponse {
    private String transactionCode;
    private String businessStatusCode;
}
