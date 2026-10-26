CREATE TABLE repair_requests (
 id UUID PRIMARY KEY,
 contract_id UUID NOT NULL REFERENCES rental_contracts(id),
 printer_id UUID NOT NULL REFERENCES printers(id),
 owner_subject VARCHAR(255),
 description VARCHAR(4000) NOT NULL CHECK (length(btrim(description)) BETWEEN 10 AND 4000),
 status VARCHAR(20) NOT NULL CHECK (status IN ('OPEN','IN_PROGRESS','COMPLETED','CANCELLED')),
 resolution VARCHAR(2000),
 created_at TIMESTAMPTZ NOT NULL,
 updated_at TIMESTAMPTZ NOT NULL CHECK (updated_at >= created_at),
 version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
 CHECK ((status='COMPLETED' AND resolution IS NOT NULL AND length(btrim(resolution)) BETWEEN 5 AND 2000)
     OR (status<>'COMPLETED' AND resolution IS NULL))
);
CREATE UNIQUE INDEX repair_requests_one_open_contract ON repair_requests(contract_id)
 WHERE status IN ('OPEN','IN_PROGRESS');
CREATE INDEX repair_requests_owner_created_idx ON repair_requests(owner_subject,created_at DESC,id DESC);
CREATE INDEX repair_requests_created_idx ON repair_requests(created_at DESC,id DESC);
CREATE INDEX repair_requests_contract_created_idx ON repair_requests(contract_id,created_at DESC,id DESC);
