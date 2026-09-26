package com.TrucVanban.auditlog.domain;

public record AuditInvocation(
        AuditOperation operation,
        Object[] arguments,
        Object result,
        Throwable error,
        Object beforeSnapshot
) {
}

