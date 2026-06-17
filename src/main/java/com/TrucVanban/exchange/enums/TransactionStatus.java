package com.TrucVanban.exchange.enums;

public enum TransactionStatus {
    RECEIVED("Mới nhận"),
    VALIDATED("Check chữ ký OK"),
    ROUTED("Nằm trong Queue chờ bốc"),
    DISPATCHED("Đang gọi HTTP sang đích"),
    DELIVERED("Thành công 200 OK"),
    FAILED("Đứt mạng, Sập endpoint");

    public final String description;

    TransactionStatus(String description) {
        this.description = description;
    }
}
