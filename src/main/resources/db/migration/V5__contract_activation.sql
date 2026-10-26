ALTER TABLE rental_contracts ADD COLUMN initial_mono_reading BIGINT;
ALTER TABLE rental_contracts ADD COLUMN initial_color_reading BIGINT;
ALTER TABLE rental_contracts ADD COLUMN initial_reading_date DATE;
ALTER TABLE rental_contracts ADD COLUMN activated_at TIMESTAMPTZ;

ALTER TABLE rental_contracts ADD CONSTRAINT contract_initial_readings_valid CHECK (
    (status = 'DRAFT' AND initial_mono_reading IS NULL AND initial_color_reading IS NULL
        AND initial_reading_date IS NULL AND activated_at IS NULL)
    OR
    (status IN ('ACTIVE', 'COMPLETED')
        AND initial_mono_reading IS NOT NULL AND initial_mono_reading >= 0
        AND initial_color_reading IS NOT NULL AND initial_color_reading >= 0
        AND initial_reading_date IS NOT NULL AND initial_reading_date = start_date
        AND activated_at IS NOT NULL)
);
