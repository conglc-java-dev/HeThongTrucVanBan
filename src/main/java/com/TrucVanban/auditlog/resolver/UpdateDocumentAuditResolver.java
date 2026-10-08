package com.TrucVanban.auditlog.resolver;

import com.TrucVanban.auditlog.domain.AuditAction;
import com.TrucVanban.auditlog.domain.AuditEventCommand;
import com.TrucVanban.auditlog.domain.AuditInvocation;
import com.TrucVanban.auditlog.domain.AuditOutcome;
import com.TrucVanban.auditlog.domain.AuditVisibilityScope;
import com.TrucVanban.auditlog.service.AuditError;
import com.TrucVanban.auditlog.service.AuditErrorClassifier;
import com.TrucVanban.exchange.dto.request.UpdateDocumentRequest;
import com.TrucVanban.exchange.entity.Document;
import com.TrucVanban.exchange.repository.DocumentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class UpdateDocumentAuditResolver implements AuditEventResolver {
    private static final String DEFAULT_UPDATE_REASON = "Cập nhật thông tin văn bản";

    private final DocumentRepository documentRepository;
    private final AuditErrorClassifier errorClassifier;

    @Override
    public Object captureBefore(Object[] arguments) {
        String documentCode = (String) arguments[0];
        return documentRepository.findByDocumentCode(documentCode)
                .map(this::snapshot)
                .orElse(null);
    }

    @Override
    public List<AuditEventCommand> resolveSuccess(AuditInvocation invocation) {
        String documentCode = (String) invocation.arguments()[0];
        UpdateDocumentRequest request = (UpdateDocumentRequest) invocation.arguments()[1];
        DocumentSnapshot before = (DocumentSnapshot) invocation.beforeSnapshot();
        DocumentSnapshot after = documentRepository.findByDocumentCode(documentCode)
                .map(this::snapshot)
                .orElseThrow();

        List<String> changedFields = changedFields(before, after);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("changedFields", changedFields);
        metadata.put("versionNo", after.currentVersion());

        return List.of(AuditEventCommand.builder()
                .action(AuditAction.DOCUMENT_UPDATED)
                .outcome(AuditOutcome.SUCCESS)
                .actorType("ORGANIZATION")
                .actorId(request.getRequesterCode())
                .actorOrganizationId(after.senderOrgId())
                .actorOrganizationCode(request.getRequesterCode())
                .documentId(after.documentId())
                .documentCode(after.documentCode())
                .reason(hasText(request.getUpdateReason()) ? request.getUpdateReason() : DEFAULT_UPDATE_REASON)
                .metadata(metadata)
                .beforeState(state(before))
                .afterState(state(after))
                .visibilityScope(AuditVisibilityScope.TRANSACTION_PARTICIPANTS)
                .senderOrganizationId(after.senderOrgId())
                .build());
    }

    @Override
    public List<AuditEventCommand> resolveFailure(AuditInvocation invocation) {
        String documentCode = (String) invocation.arguments()[0];
        UpdateDocumentRequest request = (UpdateDocumentRequest) invocation.arguments()[1];
        DocumentSnapshot before = (DocumentSnapshot) invocation.beforeSnapshot();
        AuditError error = errorClassifier.classify(invocation.error());
        return List.of(AuditEventCommand.builder()
                .action(AuditAction.DOCUMENT_UPDATED)
                .outcome(error.outcome())
                .actorType("ORGANIZATION")
                .actorId(request.getRequesterCode())
                .actorOrganizationId(before != null ? before.senderOrgId() : null)
                .actorOrganizationCode(request.getRequesterCode())
                .documentId(before != null ? before.documentId() : null)
                .documentCode(documentCode)
                .reason(request.getUpdateReason())
                .errorCode(error.code())
                .errorMessage(error.message())
                .visibilityScope(AuditVisibilityScope.GATEWAY_ONLY)
                .build());
    }

    private DocumentSnapshot snapshot(Document document) {
        return new DocumentSnapshot(document.getId(), document.getDocumentCode(), document.getSenderOrgId(),
                document.getTitle(), document.getSummary(), document.getDocumentType(),
                document.getExtractedMetadata(), document.getCurrentVersion(), document.getStatus().name());
    }

    private List<String> changedFields(DocumentSnapshot before, DocumentSnapshot after) {
        if (before == null) return List.of();
        List<String> fields = new ArrayList<>();
        if (!Objects.equals(before.title(), after.title())) fields.add("title");
        if (!Objects.equals(before.summary(), after.summary())) fields.add("summary");
        if (!Objects.equals(before.documentType(), after.documentType())) fields.add("documentType");
        if (!Objects.equals(before.extractedMetadata(), after.extractedMetadata())) fields.add("extractedMetadata");
        if (!Objects.equals(before.currentVersion(), after.currentVersion())) fields.add("currentVersion");
        if (!Objects.equals(before.status(), after.status())) fields.add("status");
        return fields;
    }

    private Map<String, Object> state(DocumentSnapshot snapshot) {
        if (snapshot == null) return Map.of();
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("currentVersion", snapshot.currentVersion());
        state.put("status", snapshot.status());
        state.values().removeIf(Objects::isNull);
        return state;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record DocumentSnapshot(Long documentId, String documentCode, Long senderOrgId,
                                    String title, String summary, String documentType,
                                    JsonNode extractedMetadata, Integer currentVersion, String status) {
    }
}
