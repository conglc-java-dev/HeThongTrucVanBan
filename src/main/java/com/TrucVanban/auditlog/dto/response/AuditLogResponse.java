package com.TrucVanban.auditlog.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.time.OffsetDateTime;
import java.util.UUID;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditLogResponse(
        UUID eventId,
        String category,
        String action,
        String result,
        String actorOrganizationCode,
        String documentCode,
        String transactionCode,
        String masterTransactionCode,
        String requestId,
        String sourceIp,
        String userAgent,
        String errorCode,
        String errorMessage,
        String reason,
        JsonNode metadata,
        JsonNode beforeState,
        JsonNode afterState,
        String visibilityScope,
        OffsetDateTime createdAt
) {
}
