package com.TrucVanban.exchange.entity;

import com.TrucVanban.exchange.dto.command.ExchangeTransactionCreateCommand;
import com.TrucVanban.shared.utils.NumberUtils;
import com.TrucVanban.shared.utils.StringUtils;
import com.TrucVanban.exchange.enums.SignatureStatus;
import com.TrucVanban.exchange.enums.TransactionStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "exchange_transactions")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExchangeTransactions {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_code", unique = true, nullable = false, length = 50)
    private String transactionCode;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "sender_org_id", nullable = false)
    private Long senderOrgId;

    @Column(name = "receiver_org_id", nullable = false)
    private Long receiverOrgId;

    @Column(nullable = false)
    private Integer priority;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_status", length = 30)
    private TransactionStatus currentStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "signature_status", length = 30)
    private SignatureStatus signatureStatus;

    @Column(name = "sla_deadline")
    private LocalDateTime slaDeadline;

    @Version
    @Builder.Default
    @Column(name = "version")
    private Integer version = 0;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public static ExchangeTransactions of(ExchangeTransactionCreateCommand command) {

        if(command == null) throw new IllegalArgumentException("Command must not be null");
        if(StringUtils.isNullOrBlank(command.getTransactionCode())) throw new IllegalArgumentException("Transaction code must not be null or empty");
        if(NumberUtils.isNullOrNegative(command.getDocumentId())) throw new IllegalArgumentException("Document ID must be a positive number");
        if(NumberUtils.isNullOrNegative(command.getSenderOrgId())) throw new IllegalArgumentException("Sender organization ID must be a positive number");
        if(NumberUtils.isNullOrNegative(command.getReceiverOrgId())) throw new IllegalArgumentException("Receiver organization ID must be a positive number");
        if(NumberUtils.isNullOrNegative(command.getPriority())) throw new IllegalArgumentException("Priority must be a non-negative integer");

        return ExchangeTransactions.builder()
                .transactionCode(command.getTransactionCode())
                .documentId(command.getDocumentId())
                .senderOrgId(command.getSenderOrgId())
                .receiverOrgId(command.getReceiverOrgId())
                .priority(command.getPriority())
                .currentStatus(command.getCurrentStatus())
                .signatureStatus(command.getSignatureStatus())
                .slaDeadline(command.getSlaDeadline())
                .build();
    }

}
