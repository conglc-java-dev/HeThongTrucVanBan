package com.TrucVanban.auditlog.resolver;

import com.TrucVanban.auditlog.domain.AuditEventCommand;
import com.TrucVanban.auditlog.domain.AuditInvocation;

import java.util.List;

public interface AuditEventResolver {
    default Object captureBefore(Object[] arguments) {
        return null;
    }

    List<AuditEventCommand> resolveSuccess(AuditInvocation invocation);

    List<AuditEventCommand> resolveFailure(AuditInvocation invocation);
}

