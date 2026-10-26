-- Keep existing resource assignments. New requests do not select a printer.
ALTER TABLE rental_requests ALTER COLUMN resource_id DROP NOT NULL;

-- Existing rows have unknown requirements, so do not invent historical values.
-- New requests require both values through API validation.
ALTER TABLE rental_requests ADD COLUMN estimated_monthly_pages INTEGER;
ALTER TABLE rental_requests ADD COLUMN color_printing_required BOOLEAN;
ALTER TABLE rental_requests ADD CONSTRAINT rental_monthly_pages_positive
    CHECK (estimated_monthly_pages IS NULL OR estimated_monthly_pages > 0);

ALTER TABLE rental_requests DROP CONSTRAINT rental_status_valid;
ALTER TABLE rental_requests ADD CONSTRAINT rental_status_valid
    CHECK (status IN ('PENDING', 'IN_REVIEW', 'APPROVED', 'REJECTED', 'CANCELLED', 'COMPLETED'));
