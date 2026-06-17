package com.TrucVanban.exchange.dto.command;

import com.TrucVanban.exchange.enums.SignatureStatus;
import com.TrucVanban.exchange.enums.TransactionStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ExchangeTransactionCreateCommand {
    private String transactionCode;
    private Long documentId;
    private Long senderOrgId;
    private Long receiverOrgId;
    private Integer priority;
    private TransactionStatus currentStatus;
    private SignatureStatus signatureStatus;
    private LocalDateTime slaDeadline;
}
