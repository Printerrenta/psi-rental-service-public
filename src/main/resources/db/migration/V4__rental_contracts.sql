CREATE TABLE rental_contracts (
    id UUID PRIMARY KEY,
    rental_request_id UUID NOT NULL UNIQUE REFERENCES rental_requests(id),
    printer_id UUID NOT NULL REFERENCES printers(id),
    customer_name VARCHAR(120) NOT NULL,
    customer_email VARCHAR(254) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    monthly_fee NUMERIC(12,2) NOT NULL CHECK (monthly_fee >= 0),
    included_mono_pages INTEGER NOT NULL CHECK (included_mono_pages >= 0),
    included_color_pages INTEGER NOT NULL CHECK (included_color_pages >= 0),
    mono_page_price NUMERIC(10,4) NOT NULL CHECK (mono_page_price >= 0),
    color_page_price NUMERIC(10,4) NOT NULL CHECK (color_page_price >= 0),
    currency VARCHAR(3) NOT NULL CHECK (currency = 'EUR'),
    status VARCHAR(20) NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'COMPLETED')),
    created_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    CHECK (end_date >= start_date)
);
CREATE INDEX rental_contracts_created_idx ON rental_contracts (created_at DESC, id DESC);
