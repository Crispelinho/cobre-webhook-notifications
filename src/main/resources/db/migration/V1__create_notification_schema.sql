-- Notification platform schema. IDs are varchar so seeded IDs (EVT001) and UUIDv7 strings share the same columns.

CREATE TABLE events (
    id          VARCHAR(64)  PRIMARY KEY,
    client_id   VARCHAR(64)  NOT NULL,
    event_type  VARCHAR(100) NOT NULL,
    content     TEXT         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL
);

CREATE TABLE subscriptions (
    id                VARCHAR(64)   PRIMARY KEY,
    client_id         VARCHAR(64)   NOT NULL,
    url               VARCHAR(2048) NOT NULL,
    event_types       TEXT[]        NOT NULL,
    active            BOOLEAN       NOT NULL DEFAULT TRUE,
    secret_ciphertext BYTEA,
    created_at        TIMESTAMPTZ   NOT NULL
);

-- Matching looks up the active subscriptions of one client.
CREATE INDEX idx_subscriptions_client_active ON subscriptions (client_id) WHERE active;

CREATE TABLE notifications (
    id              VARCHAR(64)   PRIMARY KEY,
    event_id        VARCHAR(64)   NOT NULL REFERENCES events (id),
    subscription_id VARCHAR(64)   NOT NULL REFERENCES subscriptions (id),
    -- Denormalized so every self-service query filters on one indexed column.
    client_id       VARCHAR(64)   NOT NULL,
    -- Snapshot of the subscription URL at creation time; history stays immutable.
    destination_url VARCHAR(2048) NOT NULL,
    delivery_status VARCHAR(16)   NOT NULL
        CONSTRAINT ck_notifications_delivery_status
            CHECK (delivery_status IN ('pending', 'retrying', 'completed', 'failed')),
    round           INTEGER       NOT NULL DEFAULT 1 CHECK (round >= 1),
    attempts        INTEGER       NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    created_at      TIMESTAMPTZ   NOT NULL,
    -- Final outcome of the current round (completed or failed); null while pending.
    delivery_date   TIMESTAMPTZ,
    -- First successful (2xx) attempt; null if there was none.
    delivered_at    TIMESTAMPTZ,
    last_error      VARCHAR(255),
    -- Optimistic locking: two concurrent replays cannot both win.
    version         BIGINT        NOT NULL DEFAULT 0
);

-- The two self-service query shapes: by creation date, and by status then creation date.
CREATE INDEX idx_notifications_client_created
    ON notifications (client_id, created_at DESC, id DESC);
CREATE INDEX idx_notifications_client_status_created
    ON notifications (client_id, delivery_status, created_at DESC);

CREATE TABLE delivery_attempts (
    id              BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    notification_id VARCHAR(64) NOT NULL REFERENCES notifications (id),
    round           INTEGER     NOT NULL,
    attempt_number  INTEGER     NOT NULL,
    attempted_at    TIMESTAMPTZ NOT NULL,
    http_status     INTEGER,
    error_type      VARCHAR(64),
    latency_ms      INTEGER,
    -- Makes recording an attempt idempotent under at-least-once delivery.
    CONSTRAINT uq_delivery_attempts UNIQUE (notification_id, round, attempt_number)
);
