package com.TrucVanban.exchange.dto.response;

import com.TrucVanban.exchange.enums.DocumentStatus;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class DocumentDetailResponse {
    private String documentCode;
    private String title;
    private String summary;
    private String documentType;
    private JsonNode extractedMetadata;
    private Long senderOrgId;
    private DocumentStatus status;
    private Integer currentVersion;
    private List<VersionResponse> versions;
    private List<VersionResponse> historyVersions;
    private List<ReplacementRelationResponse> replacements;
    private List<AuditResponse> auditLogs;

    @Data @Builder
    public static class VersionResponse {
        private Integer versionNo;
        private String storagePath;
        private String checksum;
        private String updateReason;
        private String createdBy;
        private LocalDateTime createdAt;
        private List<String> changedFields;
    }

    @Data @Builder
    public static class ReplacementRelationResponse {
        private String replacementDocumentCode;
        private String replacedDocumentCode;
        private String reason;
        private LocalDateTime createdAt;
    }

    @Data @Builder
    public static class AuditResponse {
        private String action;
        private String actorType;
        private String actorId;
        private String result;
        private String detail;
        private LocalDateTime createdAt;
    }
}