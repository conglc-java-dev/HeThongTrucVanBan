-- =====================================================================
-- V13: Tách bảng visual assets của cơ quan ra khỏi organizations
-- Phân hệ: Visual Signature Asset Management
-- Phiên bản: 1.0 | Ngày: 2026-08-20
-- =====================================================================
-- Lý do: Mỗi cơ quan có thể có nhiều loại dấu (STAMP_MAIN, STAMP_SECRET...)
-- và nhiều người ký (Bộ trưởng, Thứ trưởng). Bảng organizations cũ không
-- đủ linh hoạt để quản lý vòng đời (valid_from/to), trạng thái is_active,
-- và phân loại từng loại asset.
-- =====================================================================

CREATE TABLE organization_visual_assets (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,

    -- Loại asset: STAMP_MAIN | STAMP_SECRET | STAMP_URGENT | STAMP_CONFIDENTIAL | SIGNATURE_LEADER
    asset_type      VARCHAR(30) NOT NULL,

    -- Tên hiển thị thân thiện, VD: 'Con dấu Bộ GD&ĐT', 'Chữ ký Thứ trưởng A'
    asset_name      VARCHAR(100) NOT NULL,

    -- Object key MinIO (stamps/A_BGDDT.png) hoặc URL ngoài (https://...)
    image_url       VARCHAR(500) NOT NULL,

    -- Chức danh người ký (chỉ dùng với SIGNATURE_LEADER)
    signer_title    VARCHAR(100),

    -- Chỉ 1 asset mặc định mỗi loại per cơ quan được dùng khi không chỉ định
    is_default      BOOLEAN NOT NULL DEFAULT FALSE,

    is_active       BOOLEAN NOT NULL DEFAULT TRUE,

    valid_from      TIMESTAMPTZ,
    valid_to        TIMESTAMPTZ,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Index tra cứu chính: tìm asset mặc định của cơ quan theo loại
CREATE INDEX idx_org_assets_lookup
    ON organization_visual_assets(organization_id, asset_type, is_active);

COMMENT ON TABLE organization_visual_assets IS
    'Lưu trữ ảnh con dấu và chữ ký của từng cơ quan. Hỗ trợ nhiều loại dấu và nhiều người ký.';

COMMENT ON COLUMN organization_visual_assets.asset_type IS
    'Enum: STAMP_MAIN, STAMP_SECRET, STAMP_URGENT, STAMP_CONFIDENTIAL, SIGNATURE_LEADER';

COMMENT ON COLUMN organization_visual_assets.image_url IS
    'Object key MinIO (VD: stamps/A_BGDDT.png) hoặc URL ngoài bắt đầu bằng http/https';

-- -----------------------------------------------------------------------
-- Seed dữ liệu test: Migrate dữ liệu hiện có từ organizations sang bảng mới
-- -----------------------------------------------------------------------
INSERT INTO organization_visual_assets (organization_id, asset_type, asset_name, image_url, is_default)
SELECT id,
       'STAMP_MAIN',
       'Con dấu chính - ' || name,
       'https://blogger.googleusercontent.com/img/b/R29vZ2xl/AVvXsEiMCQrknIIaf67d-092R6REk3W3Eu5TGK-6KMo99yKzvTvxT7AYsvnuU3u57jyQ8L4WPk6EmuxIizupipYN4EeBVuzTOjcEFQacxoY4DZlpCx0Nah4PN2Hcc-Ok6YmXENL17ApHLKCL9BCpADeqSWvYIMo6bPDQkuszqAnj0BUfrfZgYbEAedWzujIblw/s610/tach-con-dau-bang-photoshop-4.jpg',
       true
FROM organizations
WHERE code = 'A_BGDDT';

INSERT INTO organization_visual_assets (organization_id, asset_type, asset_name, image_url, is_default)
SELECT id,
       'SIGNATURE_LEADER',
       'Chữ ký lãnh đạo - ' || name,
       'https://cdn-media.sforum.vn/storage/app/media/tach-chu-ky-trong-photoshop-13.jpg',
       true
FROM organizations
WHERE code = 'A_BGDDT';

-- Seed cho B_BTC (dùng cùng URL test)
INSERT INTO organization_visual_assets (organization_id, asset_type, asset_name, image_url, is_default)
SELECT id,
       'STAMP_MAIN',
       'Con dấu chính - ' || name,
       'https://blogger.googleusercontent.com/img/b/R29vZ2xl/AVvXsEiMCQrknIIaf67d-092R6REk3W3Eu5TGK-6KMo99yKzvTvxT7AYsvnuU3u57jyQ8L4WPk6EmuxIizupipYN4EeBVuzTOjcEFQacxoY4DZlpCx0Nah4PN2Hcc-Ok6YmXENL17ApHLKCL9BCpADeqSWvYIMo6bPDQkuszqAnj0BUfrfZgYbEAedWzujIblw/s610/tach-con-dau-bang-photoshop-4.jpg',
       true
FROM organizations
WHERE code = 'B_BTC';

INSERT INTO organization_visual_assets (organization_id, asset_type, asset_name, image_url, is_default)
SELECT id,
       'SIGNATURE_LEADER',
       'Chữ ký lãnh đạo - ' || name,
       'https://cdn-media.sforum.vn/storage/app/media/tach-chu-ky-trong-photoshop-13.jpg',
       true
FROM organizations
WHERE code = 'B_BTC';
