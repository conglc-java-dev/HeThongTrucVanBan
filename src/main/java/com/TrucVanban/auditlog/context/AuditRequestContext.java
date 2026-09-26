package com.TrucVanban.auditlog.context;

public record AuditRequestContext(
        String requestId,
        String correlationId,
        String sourceIp,
        String userAgent
) {
    public static AuditRequestContext empty() {
        return new AuditRequestContext(null, null, null, null);
    }
}

