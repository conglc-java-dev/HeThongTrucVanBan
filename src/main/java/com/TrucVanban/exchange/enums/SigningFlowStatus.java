package com.TrucVanban.exchange.enums;

/**
 * Trạng thái luồng ký số (Document Signing Flow).
 * Tách biệt hoàn toàn với TransactionStatus (routing/dispatch).
 */
public enum SigningFlowStatus {

    INITIATED("Đã khởi tạo, đang chờ bước ký đầu tiên"),
    WAITING_FOR_ROUTING_SIGN("Đang chờ các bên ký nối tiếp"),
    COMPLETED_READY_FOR_DISTRIBUTION("Đã ký xong toàn bộ, đang phân phối"),
    COMPLETED("Đã hoàn tất — văn bản đã được phân phối tới tất cả các bên"),
    REJECTED("Bị từ chối — chữ ký không hợp lệ hoặc vi phạm quy trình");

    public final String description;

    SigningFlowStatus(String description) {
        this.description = description;
    }
}
