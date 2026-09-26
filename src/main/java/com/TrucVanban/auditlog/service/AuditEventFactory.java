package com.TrucVanban.auditlog.service;

import com.TrucVanban.auditlog.context.AuditRequestContext;
import com.TrucVanban.auditlog.context.AuditRequestContextProvider;
import com.TrucVanban.auditlog.domain.*;
import com.TrucVanban.auditlog.entity.AuditLog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AuditEventFactory {
    private final ObjectMapper objectMapper;
    private final AuditMetadataSanitizer sanitizer;
    private final AuditRequestContextProvider contextProvider;

    public AuditLog create(AuditEventCommand command) {
        validate(command);
        AuditRequestContext context = contextProvider.getCurrentContext();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        return AuditLog.builder()
                .eventId(UUID.randomUUID())
                .schemaVersion(1)
                .source("GATEWAY")
                .category(command.action().getCategory().name())
                .action(command.action().name())
                .result(command.outcome().name())
                .actorType(command.actorType())
                .actorId(command.actorId())
                .actorOrganizationId(command.actorOrganizationId())
                .actorOrganizationCode(command.actorOrganizationCode())
                .targetType(command.action().getTargetType().name())
                .documentId(command.documentId())
                .documentCode(command.documentCode())
                .transactionId(command.transactionId())
                .transactionCode(command.transactionCode())
                .masterTransactionCode(command.masterTransactionCode())
                .requestId(context.requestId())
                .correlationId(context.correlationId())
                .sourceIp(context.sourceIp())
                .userAgent(context.userAgent())
                .errorCode(command.errorCode())
                .errorMessage(command.errorMessage())
                .reason(command.reason())
                .detail(toJson(sanitizer.sanitize(command.metadata())))
                .beforeState(toJson(sanitizer.sanitize(command.beforeState())))
                .afterState(toJson(sanitizer.sanitize(command.afterState())))
                .visibilityScope((command.visibilityScope() != null
                        ? command.visibilityScope()
                        : AuditVisibilityScope.GATEWAY_ONLY).name())
                .senderOrganizationId(command.senderOrganizationId())
                .receiverOrganizationId(command.receiverOrganizationId())
                .createdAt(now)
                .recordedAt(now)
                .build();
    }

    private void validate(AuditEventCommand command) {
        if (command == null || command.action() == null || command.outcome() == null) {
            throw new IllegalArgumentException("Audit action và outcome là bắt buộc");
        }
        if ((command.outcome() == AuditOutcome.FAILURE || command.outcome() == AuditOutcome.REJECTED)
                && (command.errorCode() == null || command.errorCode().isBlank())) {
            throw new IllegalArgumentException("Audit failure/rejected bắt buộc có errorCode");
        }
    }

    private JsonNode toJson(Map<String, Object> value) {
        return value == null || value.isEmpty() ? null : objectMapper.valueToTree(value);
    }
}
