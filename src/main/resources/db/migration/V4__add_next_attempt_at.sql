-- Retry-After: when a receiver answers 429 or 503 with a valid Retry-After, the next attempt is stored here instead
-- of going through a retry topic. A scheduler enqueues the dispatch message when it is due and clears the column.
ALTER TABLE notifications ADD COLUMN next_attempt_at TIMESTAMPTZ;

-- The scheduler scans only open rows that wait for a due time; the index stays tiny.
CREATE INDEX idx_notifications_next_attempt ON notifications (next_attempt_at)
    WHERE next_attempt_at IS NOT NULL AND delivery_status IN ('pending', 'retrying');
