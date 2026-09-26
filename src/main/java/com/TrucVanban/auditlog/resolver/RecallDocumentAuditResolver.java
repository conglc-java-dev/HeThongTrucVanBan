package com.TrucVanban.auditlog.resolver;

import com.TrucVanban.auditlog.domain.*;
import com.TrucVanban.auditlog.service.AuditError;
import com.TrucVanban.auditlog.service.AuditErrorClassifier;
import com.TrucVanban.exchange.dto.request.RevokeDocumentRequest;
import com.TrucVanban.exchange.dto.response.RevokeDocumentResponse;
import com.TrucVanban.exchange.entity.Document;
import com.TrucVanban.exchange.entity.ExchangeTransactions;
import com.TrucVanban.exchange.repository.DocumentRepository;
import com.TrucVanban.exchange.repository.ExchangeTransactionsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class RecallDocumentAuditResolver implements AuditEventResolver {
    private final DocumentRepository documentRepository;
    private final ExchangeTransactionsRepository transactionRepository;
    private final AuditErrorClassifier errorClassifier;

    @Override
    public Object captureBefore(Object[] arguments) {
        String documentCode = (String) arguments[0];
        return documentRepository.findByDocumentCode(documentCode)
                .map(document -> new DocumentSnapshot(
                        document.getId(), document.getDocumentCode(), document.getSenderOrgId(),
                        document.getStatus().name()))
                .orElse(null);
    }

    @Override
    public List<AuditEventCommand> resolveSuccess(AuditInvocation invocation) {
        RevokeDocumentRequest request = (RevokeDocumentRequest) invocation.arguments()[1];
        RevokeDocumentResponse response = (RevokeDocumentResponse) invocation.result();
        DocumentSnapshot snapshot = (DocumentSnapshot) invocation.beforeSnapshot();
        ExchangeTransactions transaction = transactionRepository
                .findByTransactionCode(response.getTransactionCode()).orElseThrow();
        return List.of(AuditEventCommand.builder()
                .action(AuditAction.DOCUMENT_RECALLED)
                .outcome(AuditOutcome.SUCCESS)
                .actorType("ORGANIZATION")
                .actorId(request.getRequesterCode())
                .actorOrganizationId(snapshot.senderOrgId())
                .actorOrganizationCode(request.getRequesterCode())
                .documentId(snapshot.documentId())
                .documentCode(snapshot.documentCode())
                .transactionId(transaction.getId())
                .transactionCode(transaction.getTransactionCode())
                .reason(request.getReason())
                .beforeState(Map.of("documentStatus", snapshot.status()))
                .afterState(Map.of("documentStatus", "RECALLED"))
                .visibilityScope(AuditVisibilityScope.TRANSACTION_PARTICIPANTS)
                .senderOrganizationId(transaction.getSenderOrgId())
                .receiverOrganizationId(transaction.getReceiverOrgId())
                .build());
    }

    @Override
    public List<AuditEventCommand> resolveFailure(AuditInvocation invocation) {
        String documentCode = (String) invocation.arguments()[0];
        RevokeDocumentRequest request = (RevokeDocumentRequest) invocation.arguments()[1];
        DocumentSnapshot snapshot = (DocumentSnapshot) invocation.beforeSnapshot();
        AuditError error = errorClassifier.classify(invocation.error());
        return List.of(AuditEventCommand.builder()
                .action(AuditAction.DOCUMENT_RECALLED)
                .outcome(error.outcome())
                .actorType("ORGANIZATION")
                .actorId(request.getRequesterCode())
                .actorOrganizationId(snapshot != null ? snapshot.senderOrgId() : null)
                .actorOrganizationCode(request.getRequesterCode())
                .documentId(snapshot != null ? snapshot.documentId() : null)
                .documentCode(documentCode)
                .reason(request.getReason())
                .errorCode(error.code())
                .errorMessage(error.message())
                .visibilityScope(AuditVisibilityScope.GATEWAY_ONLY)
                .build());
    }

    private record DocumentSnapshot(Long documentId, String documentCode, Long senderOrgId, String status) {
    }
}
