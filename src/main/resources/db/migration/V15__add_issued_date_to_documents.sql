ALTER TABLE documents
    ADD COLUMN IF NOT EXISTS issued_date VARCHAR(40);

COMMENT ON COLUMN documents.issued_date IS
    'Ngày/thời gian phát hành văn bản theo dd-MM-yyyy, ISO 8601 hoặc YYYY-MM-DD';
