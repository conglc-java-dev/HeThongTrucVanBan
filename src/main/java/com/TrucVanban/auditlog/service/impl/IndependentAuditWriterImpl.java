package com.TrucVanban.auditlog.service.impl;

import com.TrucVanban.auditlog.domain.AuditEventCommand;
import com.TrucVanban.auditlog.repository.AuditLogRepository;
import com.TrucVanban.auditlog.service.AuditEventFactory;
import com.TrucVanban.auditlog.service.IndependentAuditWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class IndependentAuditWriterImpl implements IndependentAuditWriter {
    private final AuditLogRepository repository;
    private final AuditEventFactory factory;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(List<AuditEventCommand> commands) {
        if (commands == null || commands.isEmpty()) return;
        repository.saveAll(commands.stream().map(factory::create).toList());
    }
}

