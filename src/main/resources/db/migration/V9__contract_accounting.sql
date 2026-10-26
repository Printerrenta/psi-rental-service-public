CREATE TABLE contract_accounting (
 contract_id UUID PRIMARY KEY REFERENCES rental_contracts(id),
 a3_capable BOOLEAN NOT NULL,
 baseline JSONB NOT NULL CHECK (jsonb_typeof(baseline)='array' AND jsonb_array_length(baseline)=4),
 revision BIGINT NOT NULL DEFAULT 0 CHECK(revision>=0)
);
CREATE TABLE contract_terms (
 id UUID PRIMARY KEY,
 contract_id UUID NOT NULL REFERENCES contract_accounting(contract_id),
 effective_from DATE NOT NULL,
 monthly_fee NUMERIC(12,2) NOT NULL CHECK(monthly_fee>=0),
 mono_price NUMERIC(10,4) NOT NULL CHECK(mono_price>=0),
 color_price NUMERIC(10,4) NOT NULL CHECK(color_price>=0),
 vat_percent NUMERIC(7,4) NOT NULL CHECK(vat_percent>=0 AND vat_percent<=100),
 payment_term_days INTEGER NOT NULL CHECK(payment_term_days BETWEEN 0 AND 365),
 actor VARCHAR(255) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(contract_id,effective_from)
);
