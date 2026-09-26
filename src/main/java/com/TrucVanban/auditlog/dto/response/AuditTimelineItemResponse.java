package com.TrucVanban.auditlog.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditTimelineItemResponse(
        OffsetDateTime time,
        String documentCode,
        String transactionCode,
        String actionName,
        String actor,
        String receiverOrganization,
        String status,
        String description,
        String reason
) {
}
