-- Processing recovery (reconciliation decision 7): a lease with an expiry and a generation (fencing) token.
-- The public delivery_status values do not change; a lease only says "a worker is handling this attempt".

ALTER TABLE notifications
    -- Worker instance that holds the lease; diagnostics only, fencing uses the generation.
    ADD COLUMN lease_owner      VARCHAR(64),
    -- Null when no worker holds the notification event.
    ADD COLUMN lease_expires_at TIMESTAMPTZ,
    -- Increased by every claim and every release; a write with an older value matches no row.
    ADD COLUMN generation       BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_notifications_lease CHECK ((lease_owner IS NULL) = (lease_expires_at IS NULL));

-- The recovery sweep scans only the rows that hold a lease.
CREATE INDEX idx_notifications_lease_expiry ON notifications (lease_expires_at) WHERE lease_expires_at IS NOT NULL;
