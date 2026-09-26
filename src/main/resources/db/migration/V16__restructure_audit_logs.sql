ALTER TABLE audit_logs
    ADD COLUMN event_id UUID,
    ADD COLUMN schema_version INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN source VARCHAR(30) NOT NULL DEFAULT 'GATEWAY',
    ADD COLUMN category VARCHAR(50) NOT NULL DEFAULT 'LEGACY',
    ADD COLUMN actor_org_id BIGINT,
    ADD COLUMN actor_org_code VARCHAR(100),
    ADD COLUMN target_type VARCHAR(30) NOT NULL DEFAULT 'SYSTEM',
    ADD COLUMN document_code VARCHAR(100),
    ADD COLUMN transaction_code VARCHAR(100),
    ADD COLUMN master_transaction_code VARCHAR(100),
    ADD COLUMN request_id VARCHAR(100),
    ADD COLUMN correlation_id VARCHAR(100),
    ADD COLUMN source_ip VARCHAR(64),
    ADD COLUMN user_agent VARCHAR(500),
    ADD COLUMN error_code VARCHAR(100),
    ADD COLUMN error_message VARCHAR(1000),
    ADD COLUMN reason VARCHAR(1000),
    ADD COLUMN before_state JSONB,
    ADD COLUMN after_state JSONB,
    ADD COLUMN visibility_scope VARCHAR(50) NOT NULL DEFAULT 'GATEWAY_ONLY',
    ADD COLUMN sender_org_id BIGINT,
    ADD COLUMN receiver_org_id BIGINT,
    ADD COLUMN recorded_at TIMESTAMP WITH TIME ZONE;

UPDATE audit_logs
SET event_id = md5(random()::text || clock_timestamp()::text || id::text)::uuid,
    result = COALESCE(result, 'UNKNOWN'),
    target_type = CASE
        WHEN transaction_id IS NOT NULL THEN 'TRANSACTION'
        WHEN document_id IS NOT NULL THEN 'DOCUMENT'
        ELSE 'SYSTEM'
    END,
    recorded_at = COALESCE(created_at, now()::timestamp) AT TIME ZONE 'Asia/Bangkok',
    error_code = CASE
        WHEN result IN ('FAILURE', 'REJECTED') THEN 'LEGACY_FAILURE'
        ELSE error_code
    END
WHERE event_id IS NULL;

ALTER TABLE audit_logs
    ALTER COLUMN event_id SET NOT NULL,
    ALTER COLUMN result SET NOT NULL,
    ALTER COLUMN recorded_at SET NOT NULL,
    ALTER COLUMN created_at TYPE TIMESTAMP WITH TIME ZONE
        USING COALESCE(created_at, now()::timestamp) AT TIME ZONE 'Asia/Bangkok';

ALTER TABLE audit_logs
    ADD CONSTRAINT uk_audit_logs_event_id UNIQUE (event_id),
    ADD CONSTRAINT ck_audit_logs_failure_error_code
        CHECK (result NOT IN ('FAILURE', 'REJECTED') OR error_code IS NOT NULL);

CREATE INDEX idx_audit_logs_created_at_id ON audit_logs (created_at DESC, id DESC);
CREATE INDEX idx_audit_logs_document_code ON audit_logs (document_code, created_at DESC);
CREATE INDEX idx_audit_logs_transaction_code ON audit_logs (transaction_code, created_at DESC);
CREATE INDEX idx_audit_logs_actor_org_id ON audit_logs (actor_org_id, created_at DESC);
CREATE INDEX idx_audit_logs_action_result ON audit_logs (action, result, created_at DESC);
CREATE INDEX idx_audit_logs_correlation_id ON audit_logs (correlation_id);

