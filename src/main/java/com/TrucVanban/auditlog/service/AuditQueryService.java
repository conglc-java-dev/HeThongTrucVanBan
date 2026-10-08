package com.TrucVanban.auditlog.service;

import com.TrucVanban.auditlog.dto.request.AuditLogFilterRequest;
import com.TrucVanban.auditlog.dto.response.AuditLogPageResponse;
import com.TrucVanban.auditlog.dto.response.AuditTimelinePageResponse;

public interface AuditQueryService {
    AuditLogPageResponse search(AuditLogFilterRequest filter);

    AuditTimelinePageResponse getTimeline(String documentCode, int page, int size);
}
