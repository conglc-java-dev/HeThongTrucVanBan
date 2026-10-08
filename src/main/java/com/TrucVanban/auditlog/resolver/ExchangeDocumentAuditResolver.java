package com.TrucVanban.auditlog.resolver;

import com.TrucVanban.auditlog.domain.*;
import com.TrucVanban.auditlog.service.AuditError;
import com.TrucVanban.auditlog.service.AuditErrorClassifier;
import com.TrucVanban.exchange.dto.request.send.ExchangeDocumentRequest;
import com.TrucVanban.exchange.dto.response.ExchangeDocumentResponse;
import com.TrucVanban.exchange.entity.Document;
import com.TrucVanban.exchange.entity.ExchangeTransactions;
import com.TrucVanban.exchange.repository.DocumentRepository;
import com.TrucVanban.exchange.repository.ExchangeTransactionsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ExchangeDocumentAuditResolver implements AuditEventResolver {
    private final DocumentRepository documentRepository;
    private final ExchangeTransactionsRepository transactionRepository;
    private final AuditErrorClassifier errorClassifier;

    @Override
    public List<AuditEventCommand> resolveSuccess(AuditInvocation invocation) {
        ExchangeDocumentRequest request = (ExchangeDocumentRequest) invocation.arguments()[0];
        @SuppressWarnings("unchecked")
        List<ExchangeDocumentResponse> responses = (List<ExchangeDocumentResponse>) invocation.result();
        Document document = documentRepository.findByDocumentCode(request.getDocumentCode()).orElseThrow();

        List<AuditEventCommand> events = new ArrayList<>();
        events.add(AuditEventCommand.builder()
                .action(AuditAction.DOCUMENT_EXCHANGE_ACCEPTED)
                .outcome(AuditOutcome.SUCCESS)
                .actorType("ORGANIZATION")
                .actorId(request.getSenderCode())
                .actorOrganizationId(document.getSenderOrgId())
                .actorOrganizationCode(request.getSenderCode())
                .documentId(document.getId())
                .documentCode(document.getDocumentCode())
                .metadata(Map.of("receiverCount", responses.size(), "versionNo", document.getCurrentVersion()))
                .visibilityScope(AuditVisibilityScope.TRANSACTION_PARTICIPANTS)
                .senderOrganizationId(document.getSenderOrgId())
                .build());

        for (ExchangeDocumentResponse response : responses) {
            ExchangeTransactions transaction = transactionRepository
                    .findByTransactionCode(response.getTransactionCode()).orElseThrow();
            events.add(AuditEventCommand.builder()
                    .action(AuditAction.TRANSACTION_CREATED)
                    .outcome(AuditOutcome.SUCCESS)
                    .actorType("ORGANIZATION")
                    .actorId(request.getSenderCode())
                    .actorOrganizationId(document.getSenderOrgId())
                    .actorOrganizationCode(request.getSenderCode())
                    .documentId(document.getId())
                    .documentCode(document.getDocumentCode())
                    .transactionId(transaction.getId())
                    .transactionCode(transaction.getTransactionCode())
                    .visibilityScope(AuditVisibilityScope.TRANSACTION_PARTICIPANTS)
                    .senderOrganizationId(transaction.getSenderOrgId())
                    .receiverOrganizationId(transaction.getReceiverOrgId())
                    .build());
        }

        if (request.getReplacedDocumentCode() != null && !request.getReplacedDocumentCode().isBlank()) {
            events.add(AuditEventCommand.builder()
                    .action(AuditAction.DOCUMENT_REPLACED)
                    .outcome(AuditOutcome.SUCCESS)
                    .actorType("ORGANIZATION")
                    .actorId(request.getSenderCode())
                    .actorOrganizationId(document.getSenderOrgId())
                    .actorOrganizationCode(request.getSenderCode())
                    .documentId(document.getId())
                    .documentCode(document.getDocumentCode())
                    .metadata(Map.of(
                            "replacedDocumentCode", request.getReplacedDocumentCode(),
                            "replacementDocumentCode", request.getDocumentCode()))
                    .visibilityScope(AuditVisibilityScope.TRANSACTION_PARTICIPANTS)
                    .senderOrganizationId(document.getSenderOrgId())
                    .build());
        }
        return events;
    }

    @Override
    public List<AuditEventCommand> resolveFailure(AuditInvocation invocation) {
        ExchangeDocumentRequest request = (ExchangeDocumentRequest) invocation.arguments()[0];
        AuditError error = errorClassifier.classify(invocation.error());
        return List.of(AuditEventCommand.builder()
                .action(AuditAction.DOCUMENT_EXCHANGE_ACCEPTED)
                .outcome(error.outcome())
                .actorType("ORGANIZATION")
                .actorId(request.getSenderCode())
                .actorOrganizationCode(request.getSenderCode())
                .documentCode(request.getDocumentCode())
                .errorCode(error.code())
                .errorMessage(error.message())
                .visibilityScope(AuditVisibilityScope.GATEWAY_ONLY)
                .build());
    }
}

