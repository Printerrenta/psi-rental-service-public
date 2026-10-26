CREATE TABLE rental_requests (
    id UUID PRIMARY KEY,
    resource_id UUID NOT NULL,
    customer_name VARCHAR(120) NOT NULL,
    customer_email VARCHAR(254) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    notes VARCHAR(2000),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT rental_dates_valid CHECK (end_date >= start_date),
    CONSTRAINT rental_name_not_blank CHECK (length(trim(customer_name)) > 0),
    CONSTRAINT rental_email_not_blank CHECK (length(trim(customer_email)) > 0),
    CONSTRAINT rental_status_valid CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'COMPLETED')),
    CONSTRAINT rental_version_valid CHECK (version >= 0)
);

CREATE INDEX rental_requests_created_idx ON rental_requests (created_at DESC, id DESC);
CREATE INDEX rental_requests_status_created_idx ON rental_requests (status, created_at DESC, id DESC);
