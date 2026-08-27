CREATE TABLE failed_messages (
    id          BIGSERIAL PRIMARY KEY,
    message_id  VARCHAR(255),
    exchange    VARCHAR(255),
    routing_key VARCHAR(255),
    payload     TEXT NOT NULL,
    error_message TEXT,
    retry_count INTEGER NOT NULL DEFAULT 0,
    failed_at   TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_failed_messages_failed_at ON failed_messages (failed_at DESC);
CREATE INDEX idx_failed_messages_message_id ON failed_messages (message_id);
