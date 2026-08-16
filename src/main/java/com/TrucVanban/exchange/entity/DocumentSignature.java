package com.TrucVanban.exchange.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "document_signatures",
        indexes = {
                @Index(name = "idx_doc_sig_transaction_id", columnList = "transaction_id"),
                @Index(name = "idx_doc_sig_signer_code", columnList = "signer_code")
        })
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Builder
public class DocumentSignature {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * FK về exchange_transactions.id.
     */
    @Column(name = "transaction_id", nullable = false)
    private Long transactionId;

    /**
     * Thứ tự ký: 1 (A), 2 (B), 3 (C), 4 (D)...
     */
    @Column(name = "signature_order", nullable = false)
    private Integer signatureOrder;

    /**
     * Mã cơ quan ký. Ví dụ: "A_BGDDT", "B_BTC".
     */
    @Column(name = "signer_code", nullable = false, length = 50)
    private String signerCode;

    /**
     * Vai trò trong luồng ký: INITIATOR | REVIEWER | FINAL_APPROVER.
     */
    @Column(name = "signer_role", nullable = false, length = 30)
    private String signerRole;

    /**
     * Loại ký: INITIAL (Ký nháy) | OFFICIAL (Ký chính) | STAMP (Đóng dấu).
     */
    @Column(name = "signature_type", nullable = false, length = 20)
    private String signatureType;

    /**
     * Số serial chứng thư số của người ký.
     */
    @Column(name = "certificate_serial", nullable = false, length = 100)
    private String certificateSerial;

    /**
     * Base64 blob PKCS#7 CMS (lấy từ payload, tương ứng nét ký trong PDF).
     */
    @Column(name = "signature_value", nullable = false, columnDefinition = "TEXT")
    private String signatureValue;

    @Column(name = "byte_range", length = 200)
    private String byteRange;

    @Column(name = "file_url_at_signing", length = 500)
    private String fileUrlAtSigning;

    @Column(name = "signed_at")
    private LocalDateTime signedAt;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
