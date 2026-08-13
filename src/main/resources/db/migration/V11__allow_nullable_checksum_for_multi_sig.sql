-- V11: Cho phép checksum NULL trong document_versions
-- Lý do: Luồng ký nối (multi-signature) không có payloadChecksum tách biệt ở tầng Gateway.
-- Checksum được nhúng trong PDF signature blob, không cần lưu riêng tại đây.
ALTER TABLE document_versions ALTER COLUMN checksum DROP NOT NULL;
