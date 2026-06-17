package com.TrucVanban.exchange.dto.request.receive;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ReceiveDocumentRequest {

    @NotBlank(message = "Transaction code is required")
    private String transactionCode;

    @NotNull(message = "Receiver code is required")
    private Long receiverCode;

    @NotBlank(message = "Business status code is required")
    private String businessStatusCode;
    private String statusReason;
    private LocalDateTime processedAt;
}
