package com.TrucVanban.auditlog.resolver;

import com.TrucVanban.auditlog.domain.AuditAction;
import com.TrucVanban.auditlog.domain.AuditEventCommand;
import com.TrucVanban.auditlog.domain.AuditInvocation;
import com.TrucVanban.auditlog.domain.AuditOutcome;
import com.TrucVanban.auditlog.domain.AuditVisibilityScope;
import com.TrucVanban.auditlog.service.AuditError;
import com.TrucVanban.auditlog.service.AuditErrorClassifier;
import com.TrucVanban.exchange.dto.request.send.MultiSignatureRequest;
import com.TrucVanban.exchange.dto.response.MultiSignatureResponse;
import com.TrucVanban.exchange.entity.ExchangeTransactions;
import com.TrucVanban.exchange.repository.ExchangeTransactionsRepository;
import com.TrucVanban.registry.service.RegistryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class MultiSignatureAuditResolver implements AuditEventResolver {
    private final ExchangeTransactionsRepository transactionRepository;
    private final RegistryService registryService;
    private final AuditErrorClassifier errorClassifier;

    @Override
    public List<AuditEventCommand> resolveSuccess(AuditInvocation invocation) {
        MultiSignatureRequest request = (MultiSignatureRequest) invocation.arguments()[0];
        MultiSignatureResponse response = (MultiSignatureResponse) invocation.result();
        ExchangeTransactions transaction = transactionRepository.findById(response.getTransactionId()).orElseThrow();
        Long actorOrganizationId = registryService.getOrganizationIdByCode(request.getCurrentSenderCode());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("currentStep", response.getCurrentStep());
        metadata.put("signingFlowStatus", response.getSigningFlowStatus());
        metadata.put("verifiedSignaturesCount", response.getVerifiedSignaturesCount());
        if (response.getNextReceiver() != null) metadata.put("nextReceiver", response.getNextReceiver());

        return List.of(AuditEventCommand.builder()
                .action(AuditAction.MULTI_SIGNATURE_STEP_COMPLETED)
                .outcome(AuditOutcome.SUCCESS)
                .actorType("ORGANIZATION")
                .actorId(request.getCurrentSenderCode())
                .actorOrganizationId(actorOrganizationId)
                .actorOrganizationCode(request.getCurrentSenderCode())
                .documentId(transaction.getDocumentId())
                .documentCode(request.getDocumentCode())
                .transactionId(transaction.getId())
                .transactionCode(transaction.getTransactionCode())
                .masterTransactionCode(transaction.getMasterTransactionCode())
                .metadata(metadata)
                .visibilityScope(AuditVisibilityScope.TRANSACTION_PARTICIPANTS)
                .senderOrganizationId(transaction.getSenderOrgId())
                .receiverOrganizationId(transaction.getReceiverOrgId())
                .build());
    }

    @Override
    public List<AuditEventCommand> resolveFailure(AuditInvocation invocation) {
        MultiSignatureRequest request = (MultiSignatureRequest) invocation.arguments()[0];
        AuditError error = errorClassifier.classify(invocation.error());
        return List.of(AuditEventCommand.builder()
                .action(AuditAction.MULTI_SIGNATURE_STEP_COMPLETED)
                .outcome(error.outcome())
                .actorType("ORGANIZATION")
                .actorId(request.getCurrentSenderCode())
                .actorOrganizationCode(request.getCurrentSenderCode())
                .documentCode(request.getDocumentCode())
                .masterTransactionCode(request.getMasterTransactionCode())
                .errorCode(error.code())
                .errorMessage(error.message())
                .visibilityScope(AuditVisibilityScope.GATEWAY_ONLY)
                .build());
    }
}
