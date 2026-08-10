-- =====================================================================
-- V10: Nâng cấp exchange_transactions + Tạo document_signatures
-- Phân hệ: Luồng Xử lý Đa Chữ Ký Nối Tiếp (Multi-Signature Sequential)
-- Phiên bản: 2.0 | Ngày: 2026-08-06
-- =====================================================================

-- -----------------------------------------------------------------------
-- 1. Thêm các cột mới vào exchange_transactions
-- -----------------------------------------------------------------------

ALTER TABLE exchange_transactions
    -- Định danh chính để đối soát toàn bộ luồng ký
    -- Ví dụ: TXN-BGDDT-VPCP-001
    ADD COLUMN IF NOT EXISTS master_transaction_code VARCHAR(100) UNIQUE,

    -- Danh sách cơ quan ký nối tiếp (JSONB). Lưu 1 lần khi INITIATOR khởi tạo.
    -- Ví dụ: ["B_BTC", "C_BNV", "D_VPCP"]
    ADD COLUMN IF NOT EXISTS routing_list JSONB,

    -- Danh sách cơ quan nhận phân phối song song sau khi FINAL_APPROVER ký xong.
    -- Ví dụ: ["E_BGD", "F_BYT", "G_BKHDT"]
    ADD COLUMN IF NOT EXISTS distribution_list JSONB,

    -- Bước hiện tại trong routingList (0-based index).
    -- Tăng lên 1 sau mỗi lần REVIEWER ký thành công.
    ADD COLUMN IF NOT EXISTS current_step INTEGER DEFAULT 0,

    -- Object key MinIO của file PDF version mới nhất (sau khi ký bồi).
    -- Cập nhật sau mỗi bước ký.
    ADD COLUMN IF NOT EXISTS current_storage_path VARCHAR(500),

    -- Trạng thái luồng ký — TÁCH BIỆT với current_status (routing/dispatch).
    -- Enum: INITIATED | WAITING_FOR_ROUTING_SIGN | COMPLETED_READY_FOR_DISTRIBUTION | COMPLETED | REJECTED
    ADD COLUMN IF NOT EXISTS signing_flow_status VARCHAR(50);

-- Index tối ưu tìm kiếm theo master_transaction_code
CREATE INDEX IF NOT EXISTS idx_exchange_txn_master_code
    ON exchange_transactions (master_transaction_code);

-- Index tối ưu filter theo signing_flow_status (monitoring dashboard)
CREATE INDEX IF NOT EXISTS idx_exchange_txn_signing_flow_status
    ON exchange_transactions (signing_flow_status);

-- -----------------------------------------------------------------------
-- 2. Tạo bảng document_signatures (audit trail pháp lý)
-- -----------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS document_signatures (
    id                  BIGSERIAL PRIMARY KEY,

    -- FK về exchange_transactions
    transaction_id      BIGINT NOT NULL
        REFERENCES exchange_transactions(id) ON DELETE CASCADE,

    -- Thứ tự ký: 1 (A), 2 (B), 3 (C), 4 (D)...
    signature_order     INTEGER NOT NULL,

    -- Mã cơ quan ký. Ví dụ: "A_BGDDT", "B_BTC"
    signer_code         VARCHAR(50) NOT NULL,

    -- Vai trò trong luồng ký: INITIATOR | REVIEWER | FINAL_APPROVER
    signer_role         VARCHAR(30) NOT NULL,

    -- Loại ký: INITIAL (Ký nháy) | OFFICIAL (Ký chính) | STAMP (Đóng dấu)
    signature_type      VARCHAR(20) NOT NULL,

    -- Số serial chứng thư số
    certificate_serial  VARCHAR(100) NOT NULL,

    -- Base64 blob PKCS#7 CMS — lấy từ payload, tương ứng nét ký trong PDF
    signature_value     TEXT NOT NULL,

    -- ByteRange Gateway tự bóc từ Dictionary /Sig trong PDF
    -- Định dạng: "[offset1, length1, offset2, length2]"
    -- Dùng để audit và tái xác minh sau này
    byte_range          VARCHAR(200),

    -- StoragePath của file PDF tại thời điểm bước này được ký (audit trail)
    file_url_at_signing VARCHAR(500),

    -- Thời điểm ký (do E-Office Client báo cáo)
    signed_at           TIMESTAMP WITH TIME ZONE,

    -- Thời điểm Gateway xác minh thành công
    verified_at         TIMESTAMP WITH TIME ZONE,

    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- Index tối ưu truy vấn theo giao dịch và thứ tự ký
CREATE INDEX IF NOT EXISTS idx_doc_sig_transaction_id
    ON document_signatures (transaction_id);

CREATE INDEX IF NOT EXISTS idx_doc_sig_transaction_order
    ON document_signatures (transaction_id, signature_order);

CREATE INDEX IF NOT EXISTS idx_doc_sig_signer_code
    ON document_signatures (signer_code);

-- -----------------------------------------------------------------------
-- Comment bảng (tài liệu hóa schema)
-- -----------------------------------------------------------------------

COMMENT ON TABLE document_signatures IS
    'Audit trail pháp lý: lưu chi tiết từng nét ký trong luồng đa chữ ký nối tiếp.';

COMMENT ON COLUMN document_signatures.byte_range IS
    'ByteRange trích xuất từ PDF Dictionary /Sig. Cho phép tái xác minh độc lập sau này.';

COMMENT ON COLUMN document_signatures.file_url_at_signing IS
    'Object key MinIO của file PDF tại bước này. Khác với current_storage_path vì mỗi bước là một version khác nhau.';

COMMENT ON COLUMN exchange_transactions.master_transaction_code IS
    'Định danh duy nhất để đối soát toàn bộ luồng ký. Format: TXN-{ORG1}-{ORG2}-{SEQUENCE}.';

COMMENT ON COLUMN exchange_transactions.routing_list IS
    'Danh sách cơ quan ký nối tiếp. JSONB. Chỉ client INITIATOR gửi 1 lần lúc khởi tạo.';

COMMENT ON COLUMN exchange_transactions.distribution_list IS
    'Danh sách cơ quan nhận phân phối song song sau khi FINAL_APPROVER ký xong.';

COMMENT ON COLUMN exchange_transactions.signing_flow_status IS
    'Trạng thái luồng ký (TÁCH BIỆT với current_status dùng cho routing/dispatch).
     Enum: INITIATED | WAITING_FOR_ROUTING_SIGN | COMPLETED_READY_FOR_DISTRIBUTION | COMPLETED | REJECTED';
