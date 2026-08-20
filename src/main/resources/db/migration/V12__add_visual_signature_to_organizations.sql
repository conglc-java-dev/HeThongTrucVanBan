-- =====================================================================
-- V12: Bổ sung trường lưu ảnh con dấu & chữ ký tay vào organizations
-- Phân hệ: Visual Signature (Dual-Layer Display)
-- Phiên bản: 1.0 | Ngày: 2026-08-18
-- =====================================================================
-- Hai cột này cho phép Simulator biết ảnh PNG nào cần vẽ lên PDF
-- khi người dùng kéo thả khung dấu trên giao diện Preview.
-- Ảnh phải định dạng PNG có nền trong suốt (transparent background).
-- =====================================================================

ALTER TABLE organizations
    -- URL trỏ tới ảnh con dấu đỏ của cơ quan (PNG, nền trong suốt)
    -- Ví dụ: documents/stamps/A_BGDDT_stamp.png (object key MinIO)
    ADD COLUMN IF NOT EXISTS stamp_image_url VARCHAR(500),

    -- URL trỏ tới ảnh chữ ký tay cá nhân của người ký (nếu có)
    -- Ví dụ: documents/signatures/A_BGDDT_sig.png
    ADD COLUMN IF NOT EXISTS signature_image_url VARCHAR(500);

COMMENT ON COLUMN organizations.stamp_image_url IS
    'Object key MinIO của ảnh con dấu đỏ tròn. Định dạng PNG nền trong suốt. Vẽ lên PDF khi Simulator xử lý Visual Signature.';

COMMENT ON COLUMN organizations.signature_image_url IS
    'Object key MinIO của ảnh chữ ký tay cá nhân. Định dạng PNG nền trong suốt. Tùy chọn — không bắt buộc.';

-- -----------------------------------------------------------------------
-- Seed dữ liệu test: gán stamp_image_url cho 2 cơ quan demo
-- Giả định object key trỏ tới file PNG đã upload sẵn trên MinIO.
-- Thay đổi giá trị này theo object key thực tế trong môi trường của bạn.
-- -----------------------------------------------------------------------
UPDATE organizations
SET stamp_image_url = 'stamps/A_BGDDT_stamp.png'
WHERE code = 'A_BGDDT';

UPDATE organizations
SET stamp_image_url = 'stamps/B_BTC_stamp.png'
WHERE code = 'B_BTC';
