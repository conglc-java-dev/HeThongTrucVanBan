package com.TrucVanban.auditlog.dto.response;

import java.util.List;

public record AuditLogPageResponse(
        List<AuditLogResponse> content,
        AuditPageableResponse pageable,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
}
