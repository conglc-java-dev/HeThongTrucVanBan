package com.TrucVanban.auditlog.service;

import com.TrucVanban.auditlog.domain.AuditOutcome;

public record AuditError(AuditOutcome outcome, String code, String message) {
}

