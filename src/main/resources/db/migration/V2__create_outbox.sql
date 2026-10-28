-- Transactional outbox: one row per dispatch message, written in the ingestion transaction (D14).
-- The relay publishes unpublished rows to Kafka and stamps published_at.

CREATE TABLE outbox (
    id              BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    notification_id VARCHAR(64) NOT NULL REFERENCES notifications (id),
    round           INTEGER     NOT NULL CHECK (round >= 1),
    -- Only notification_event_id and round; the consumer loads everything else from the database.
    payload         JSONB       NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    published_at    TIMESTAMPTZ
);

-- The relay scans only unpublished rows, oldest first; the index stays small as rows are published.
CREATE INDEX idx_outbox_unpublished ON outbox (id) WHERE published_at IS NULL;
