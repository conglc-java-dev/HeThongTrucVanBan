package com.TrucVanban.auditlog.resolver;

import com.TrucVanban.auditlog.domain.*;
import com.TrucVanban.auditlog.service.AuditError;
import com.TrucVanban.auditlog.service.AuditErrorClassifier;
import com.TrucVanban.exchange.dto.request.receive.ReceiveDocumentRequest;
import com.TrucVanban.exchange.entity.ExchangeTransactions;
import com.TrucVanban.exchange.repository.ExchangeTransactionsRepository;
import com.TrucVanban.registry.service.RegistryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class AcknowledgeDocumentAuditResolver implements AuditEventResolver {
    private final ExchangeTransactionsRepository transactionRepository;
    private final RegistryService registryService;
    private final AuditErrorClassifier errorClassifier;

    @Override
    public Object captureBefore(Object[] arguments) {
        ReceiveDocumentRequest request = (ReceiveDocumentRequest) arguments[0];
        return transactionRepository.findByTransactionCode(request.getTransactionCode())
                .map(transaction -> new TransactionSnapshot(
                        transaction.getId(), transaction.getDocumentId(), transaction.getTransactionCode(),
                        transaction.getMasterTransactionCode(), transaction.getCurrentStatus().name(),
                        transaction.getSenderOrgId(), transaction.getReceiverOrgId()))
                .orElse(null);
    }

    @Override
    public List<AuditEventCommand> resolveSuccess(AuditInvocation invocation) {
        ReceiveDocumentRequest request = (ReceiveDocumentRequest) invocation.arguments()[0];
        TransactionSnapshot snapshot = (TransactionSnapshot) invocation.beforeSnapshot();
        Long receiverId = registryService.getOrganizationIdByCode(request.getReceiverCode());
        return List.of(AuditEventCommand.builder()
                .action(AuditAction.ACK_RECEIVED)
                .outcome(AuditOutcome.SUCCESS)
                .actorType("ORGANIZATION")
                .actorId(request.getReceiverCode())
                .actorOrganizationId(receiverId)
                .actorOrganizationCode(request.getReceiverCode())
                .documentId(snapshot.documentId())
                .transactionId(snapshot.transactionId())
                .transactionCode(snapshot.transactionCode())
                .masterTransactionCode(snapshot.masterTransactionCode())
                .metadata(Map.of("businessStatusCode", request.getBusinessStatusCode().getCode()))
                .beforeState(Map.of("transactionStatus", snapshot.status()))
                .afterState(Map.of("transactionStatus", "DELIVERED"))
                .visibilityScope(AuditVisibilityScope.TRANSACTION_PARTICIPANTS)
                .senderOrganizationId(snapshot.senderOrgId())
                .receiverOrganizationId(snapshot.receiverOrgId())
                .build());
    }

    @Override
    public List<AuditEventCommand> resolveFailure(AuditInvocation invocation) {
        ReceiveDocumentRequest request = (ReceiveDocumentRequest) invocation.arguments()[0];
        TransactionSnapshot snapshot = (TransactionSnapshot) invocation.beforeSnapshot();
        AuditError error = errorClassifier.classify(invocation.error());
        return List.of(AuditEventCommand.builder()
                .action(AuditAction.ACK_RECEIVED)
                .outcome(error.outcome())
                .actorType("ORGANIZATION")
                .actorId(request.getReceiverCode())
                .actorOrganizationCode(request.getReceiverCode())
                .documentId(snapshot != null ? snapshot.documentId() : null)
                .transactionId(snapshot != null ? snapshot.transactionId() : null)
                .transactionCode(request.getTransactionCode())
                .errorCode(error.code())
                .errorMessage(error.message())
                .visibilityScope(AuditVisibilityScope.GATEWAY_ONLY)
                .build());
    }

    private record TransactionSnapshot(Long transactionId, Long documentId, String transactionCode,
                                       String masterTransactionCode, String status,
                                       Long senderOrgId, Long receiverOrgId) {
    }
}

