package com.TrucVanban.auditlog.resolver;

import com.TrucVanban.auditlog.domain.AuditAction;
import com.TrucVanban.auditlog.domain.AuditEventCommand;
import com.TrucVanban.auditlog.domain.AuditInvocation;
import com.TrucVanban.auditlog.domain.AuditOperation;
import com.TrucVanban.auditlog.domain.AuditOutcome;
import com.TrucVanban.auditlog.domain.AuditVisibilityScope;
import com.TrucVanban.auditlog.service.AuditError;
import com.TrucVanban.auditlog.service.AuditErrorClassifier;
import com.TrucVanban.exchange.dto.request.action.InitRecallActionRequest;
import com.TrucVanban.exchange.dto.request.action.InitUpdateActionRequest;
import com.TrucVanban.exchange.dto.response.DocumentActionResponse;
import com.TrucVanban.exchange.entity.Document;
import com.TrucVanban.exchange.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class DocumentActionAuditResolver implements AuditEventResolver {
    private final DocumentRepository documentRepository;
    private final AuditErrorClassifier errorClassifier;

    @Override
    public Object captureBefore(Object[] arguments) {
        String documentCode = documentCode(arguments[0]);
        return documentRepository.findByDocumentCode(documentCode)
                .map(document -> new DocumentSnapshot(
                        document.getId(), document.getDocumentCode(), document.getSenderOrgId()))
                .orElse(null);
    }

    @Override
    public List<AuditEventCommand> resolveSuccess(AuditInvocation invocation) {
        Object request = invocation.arguments()[0];
        DocumentSnapshot document = (DocumentSnapshot) invocation.beforeSnapshot();
        DocumentActionResponse response = (DocumentActionResponse) invocation.result();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("actionId", response.getActionId());
        metadata.put("actionStatus", response.getActionStatus());
        metadata.put("totalReceiversNotified", response.getTotalReceiversNotified());
        if (request instanceof InitRecallActionRequest recall) {
            metadata.put("actionDocumentCode", recall.getActionDocumentCode());
        }

        return List.of(base(invocation.operation(), request, document)
                .outcome(AuditOutcome.SUCCESS)
                .reason(reason(request))
                .metadata(metadata)
                .visibilityScope(AuditVisibilityScope.TRANSACTION_PARTICIPANTS)
                .build());
    }

    @Override
    public List<AuditEventCommand> resolveFailure(AuditInvocation invocation) {
        Object request = invocation.arguments()[0];
        DocumentSnapshot document = (DocumentSnapshot) invocation.beforeSnapshot();
        AuditError error = errorClassifier.classify(invocation.error());
        return List.of(base(invocation.operation(), request, document)
                .outcome(error.outcome())
                .reason(reason(request))
                .errorCode(error.code())
                .errorMessage(error.message())
                .visibilityScope(AuditVisibilityScope.GATEWAY_ONLY)
                .build());
    }

    private AuditEventCommand.AuditEventCommandBuilder base(
            AuditOperation operation, Object request, DocumentSnapshot document) {
        String actorCode = actorCode(request);
        return AuditEventCommand.builder()
                .action(operation == AuditOperation.INIT_RECALL_ACTION
                        ? AuditAction.RECALL_ACTION_INITIATED
                        : AuditAction.UPDATE_ACTION_INITIATED)
                .actorType("ORGANIZATION")
                .actorId(actorCode)
                .actorOrganizationId(document != null ? document.senderOrgId() : null)
                .actorOrganizationCode(actorCode)
                .documentId(document != null ? document.documentId() : null)
                .documentCode(document != null ? document.documentCode() : documentCode(request))
                .senderOrganizationId(document != null ? document.senderOrgId() : null);
    }

    private String documentCode(Object request) {
        if (request instanceof InitRecallActionRequest recall) return recall.getRecalledDocumentCode();
        return ((InitUpdateActionRequest) request).getTargetDocumentCode();
    }

    private String actorCode(Object request) {
        if (request instanceof InitRecallActionRequest recall) return recall.getRequestedByCode();
        return ((InitUpdateActionRequest) request).getRequestedByCode();
    }

    private String reason(Object request) {
        if (request instanceof InitRecallActionRequest recall) return recall.getReason();
        return ((InitUpdateActionRequest) request).getReason();
    }

    private record DocumentSnapshot(Long documentId, String documentCode, Long senderOrgId) {
    }
}
