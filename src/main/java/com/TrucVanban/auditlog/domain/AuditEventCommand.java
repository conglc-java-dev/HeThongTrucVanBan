package com.TrucVanban.auditlog.domain;

import lombok.Builder;

import java.util.Map;

@Builder
public record AuditEventCommand(
        AuditAction action,
        AuditOutcome outcome,
        String actorType,
        String actorId,
        Long actorOrganizationId,
        String actorOrganizationCode,
        Long documentId,
        String documentCode,
        Long transactionId,
        String transactionCode,
        String masterTransactionCode,
        String errorCode,
        String errorMessage,
        String reason,
        Map<String, Object> metadata,
        Map<String, Object> beforeState,
        Map<String, Object> afterState,
        AuditVisibilityScope visibilityScope,
        Long senderOrganizationId,
        Long receiverOrganizationId
) {
}

