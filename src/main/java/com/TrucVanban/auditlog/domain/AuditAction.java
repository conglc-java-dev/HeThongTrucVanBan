package com.TrucVanban.auditlog.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AuditAction {
    DOCUMENT_EXCHANGE_ACCEPTED(AuditCategory.DOCUMENT, AuditTargetType.DOCUMENT, "Tiếp nhận văn bản"),
    TRANSACTION_CREATED(AuditCategory.TRANSACTION, AuditTargetType.TRANSACTION, "Khởi tạo giao dịch"),
    DOCUMENT_REPLACED(AuditCategory.DOCUMENT, AuditTargetType.DOCUMENT, "Thay thế văn bản"),
    ACK_RECEIVED(AuditCategory.TRANSACTION, AuditTargetType.TRANSACTION, "Xác nhận nhận văn bản"),
    DOCUMENT_RECALLED(AuditCategory.DOCUMENT, AuditTargetType.DOCUMENT, "Thu hồi văn bản"),
    DOCUMENT_UPDATED(AuditCategory.DOCUMENT, AuditTargetType.DOCUMENT, "Cập nhật văn bản"),
    RECALL_ACTION_INITIATED(AuditCategory.DOCUMENT, AuditTargetType.DOCUMENT, "Khởi tạo yêu cầu thu hồi"),
    UPDATE_ACTION_INITIATED(AuditCategory.DOCUMENT, AuditTargetType.DOCUMENT, "Khởi tạo yêu cầu cập nhật"),
    MULTI_SIGNATURE_STEP_COMPLETED(AuditCategory.SIGNATURE, AuditTargetType.TRANSACTION,
            "Ký số bước luân chuyển"),
    MULTI_SIGNATURE_VALIDATED(AuditCategory.SIGNATURE, AuditTargetType.SIGNATURE,
            "Xác minh tính hợp lệ chữ ký");

    private final AuditCategory category;
    private final AuditTargetType targetType;
    private final String displayName;
}
