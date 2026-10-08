package com.TrucVanban.auditlog.service;

import com.TrucVanban.auditlog.domain.AuditOutcome;
import com.TrucVanban.shared.exception.*;
import org.springframework.stereotype.Component;

@Component
public class AuditErrorClassifier {
    public AuditError classify(Throwable error) {
        if (error instanceof InvalidInputException) {
            return rejected("INVALID_INPUT", error.getMessage());
        }
        if (error instanceof ForbiddenException) {
            return rejected("ACCESS_DENIED", error.getMessage());
        }
        if (error instanceof DuplicateResourceException) {
            return rejected("DUPLICATE_RESOURCE", error.getMessage());
        }
        if (error instanceof BusinessLogicException) {
            return rejected("BUSINESS_RULE_VIOLATION", error.getMessage());
        }
        if (error instanceof ResourceNotFoundException) {
            return new AuditError(AuditOutcome.FAILURE, "RESOURCE_NOT_FOUND", safeMessage(error));
        }
        return new AuditError(AuditOutcome.FAILURE, "INTERNAL_ERROR", "Xử lý nghiệp vụ không thành công");
    }

    private AuditError rejected(String code, String message) {
        return new AuditError(AuditOutcome.REJECTED, code, truncate(message));
    }

    private String safeMessage(Throwable error) {
        return truncate(error.getMessage());
    }

    private String truncate(String value) {
        if (value == null || value.isBlank()) return "Không có thông tin chi tiết";
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}

