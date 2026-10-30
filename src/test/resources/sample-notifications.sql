-- The ten records of docs/challenge/notification_events.json, with created_at = delivery_date (seed assumption).
INSERT INTO subscriptions (id, client_id, url, event_types, created_at) VALUES
    ('SUB-CLIENT001', 'CLIENT001', 'https://webhook.example.com/CLIENT001', ARRAY['credit_card_payment'], '2024-03-01T00:00:00Z'),
    ('SUB-CLIENT002', 'CLIENT002', 'https://webhook.example.com/CLIENT002', ARRAY['credit_transfer'], '2024-03-01T00:00:00Z'),
    ('SUB-CLIENT003', 'CLIENT003', 'https://webhook.example.com/CLIENT003', ARRAY['credit_refund'], '2024-03-01T00:00:00Z');

INSERT INTO events (id, client_id, event_type, content, created_at) VALUES
    ('EVT001', 'CLIENT001', 'credit_card_payment', 'Credit card payment received for $150.00', '2024-03-15T09:30:22Z'),
    ('EVT002', 'CLIENT001', 'debit_card_withdrawal', 'ATM withdrawal of $200.00', '2024-03-15T10:15:45Z'),
    ('EVT003', 'CLIENT002', 'credit_transfer', 'Bank transfer received from Account #4567 for $1,500.00', '2024-03-15T11:20:18Z'),
    ('EVT004', 'CLIENT002', 'debit_automatic_payment', 'Monthly utility bill payment of $85.50', '2024-03-15T12:05:33Z'),
    ('EVT005', 'CLIENT003', 'credit_refund', 'Refund processed for order #789 for $45.99', '2024-03-15T13:45:10Z'),
    ('EVT006', 'CLIENT003', 'debit_transfer', 'Money transfer sent to Account #8901 for $500.00', '2024-03-15T14:30:55Z'),
    ('EVT007', 'CLIENT001', 'credit_deposit', 'Direct deposit received from Employer XYZ for $2,500.00', '2024-03-15T15:20:40Z'),
    ('EVT008', 'CLIENT002', 'debit_purchase', 'Point of sale purchase at Store ABC for $75.25', '2024-03-15T16:10:15Z'),
    ('EVT009', 'CLIENT003', 'credit_cashback', 'Cashback reward credited for $25.00', '2024-03-15T17:25:30Z'),
    ('EVT010', 'CLIENT001', 'debit_subscription', 'Monthly streaming service payment of $14.99', '2024-03-15T18:05:12Z');

INSERT INTO notifications (id, event_id, subscription_id, client_id, destination_url, delivery_status,
                           created_at, delivery_date, delivered_at) VALUES
    ('EVT001', 'EVT001', 'SUB-CLIENT001', 'CLIENT001', 'https://webhook.example.com/CLIENT001', 'completed', '2024-03-15T09:30:22Z', '2024-03-15T09:30:22Z', '2024-03-15T09:30:22Z'),
    ('EVT002', 'EVT002', 'SUB-CLIENT001', 'CLIENT001', 'https://webhook.example.com/CLIENT001', 'completed', '2024-03-15T10:15:45Z', '2024-03-15T10:15:45Z', '2024-03-15T10:15:45Z'),
    ('EVT003', 'EVT003', 'SUB-CLIENT002', 'CLIENT002', 'https://webhook.example.com/CLIENT002', 'failed',    '2024-03-15T11:20:18Z', '2024-03-15T11:20:18Z', NULL),
    ('EVT004', 'EVT004', 'SUB-CLIENT002', 'CLIENT002', 'https://webhook.example.com/CLIENT002', 'completed', '2024-03-15T12:05:33Z', '2024-03-15T12:05:33Z', '2024-03-15T12:05:33Z'),
    ('EVT005', 'EVT005', 'SUB-CLIENT003', 'CLIENT003', 'https://webhook.example.com/CLIENT003', 'failed',    '2024-03-15T13:45:10Z', '2024-03-15T13:45:10Z', NULL),
    ('EVT006', 'EVT006', 'SUB-CLIENT003', 'CLIENT003', 'https://webhook.example.com/CLIENT003', 'completed', '2024-03-15T14:30:55Z', '2024-03-15T14:30:55Z', '2024-03-15T14:30:55Z'),
    ('EVT007', 'EVT007', 'SUB-CLIENT001', 'CLIENT001', 'https://webhook.example.com/CLIENT001', 'completed', '2024-03-15T15:20:40Z', '2024-03-15T15:20:40Z', '2024-03-15T15:20:40Z'),
    ('EVT008', 'EVT008', 'SUB-CLIENT002', 'CLIENT002', 'https://webhook.example.com/CLIENT002', 'completed', '2024-03-15T16:10:15Z', '2024-03-15T16:10:15Z', '2024-03-15T16:10:15Z'),
    ('EVT009', 'EVT009', 'SUB-CLIENT003', 'CLIENT003', 'https://webhook.example.com/CLIENT003', 'failed',    '2024-03-15T17:25:30Z', '2024-03-15T17:25:30Z', NULL),
    ('EVT010', 'EVT010', 'SUB-CLIENT001', 'CLIENT001', 'https://webhook.example.com/CLIENT001', 'completed', '2024-03-15T18:05:12Z', '2024-03-15T18:05:12Z', '2024-03-15T18:05:12Z');

-- Attempts for EVT003, inserted out of order to exercise ordering by round, then attempt number.
INSERT INTO delivery_attempts (notification_id, round, attempt_number, attempted_at, http_status, error_type, latency_ms) VALUES
    ('EVT003', 2, 1, '2024-03-16T08:00:00Z', 500, 'server_error', 80),
    ('EVT003', 1, 2, '2024-03-15T11:20:18Z', 503, 'server_error', 120),
    ('EVT003', 1, 1, '2024-03-15T11:20:08Z', NULL, 'timeout', 5000);

-- EVT003 failed in round 2 after an earlier replay, so its attempts are consistent with a replay that restarts the
-- attempt numbering: a further replay starts round 3 and cannot collide with these rows.
UPDATE notifications SET round = 2 WHERE id = 'EVT003';
