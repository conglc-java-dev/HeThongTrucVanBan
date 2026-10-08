package com.TrucVanban.auditlog.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditTimelinePageResponse(
        String documentCode,
        String senderOrganization,
        String signingFlowStatus,
        String currentSigningOrganization,
        List<SigningOrganization> signingOrganizations,
        List<String> receiverOrganizations,
        List<AuditTimelineItemResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public record SigningOrganization(
            int order,
            String name,
            SigningStatus status
    ) {
    }

    public enum SigningStatus {
        COMPLETED,
        CURRENT,
        PENDING,
        REJECTED
    }
}
