package com.TrucVanban.auditlog.service;

import com.TrucVanban.auditlog.domain.AuditEventCommand;

import java.util.List;

public interface IndependentAuditWriter {
    void write(List<AuditEventCommand> commands);
}

