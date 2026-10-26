ALTER TABLE rental_requests ADD COLUMN IF NOT EXISTS owner_subject VARCHAR(255);
CREATE INDEX IF NOT EXISTS rental_requests_owner_idx ON rental_requests(owner_subject,created_at DESC,id DESC);
CREATE TABLE outbox_events (
    event_id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX outbox_pending_idx ON outbox_events(next_attempt_at,created_at) WHERE published_at IS NULL;
