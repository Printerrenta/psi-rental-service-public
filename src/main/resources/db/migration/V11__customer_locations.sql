CREATE TABLE customer_locations (
    id UUID PRIMARY KEY,
    owner_subject VARCHAR(255) NOT NULL CHECK (length(trim(owner_subject)) > 0),
    name VARCHAR(120) NOT NULL CHECK (length(trim(name)) > 0),
    address VARCHAR(255) NOT NULL CHECK (length(trim(address)) > 0),
    city VARCHAR(120) NOT NULL CHECK (length(trim(city)) > 0),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX customer_locations_owner_idx ON customer_locations(owner_subject, created_at, id);
CREATE TABLE printer_locations (
    printer_id UUID PRIMARY KEY REFERENCES printers(id),
    location_id UUID NOT NULL REFERENCES customer_locations(id),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0)
);
CREATE INDEX printer_locations_location_idx ON printer_locations(location_id);
CREATE TABLE rental_request_locations (
    request_id UUID PRIMARY KEY REFERENCES rental_requests(id),
    location_id UUID NOT NULL REFERENCES customer_locations(id)
);
CREATE INDEX rental_request_locations_location_idx ON rental_request_locations(location_id);
