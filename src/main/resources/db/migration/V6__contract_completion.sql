ALTER TABLE rental_contracts ADD COLUMN final_mono_reading BIGINT;
ALTER TABLE rental_contracts ADD COLUMN final_color_reading BIGINT;
ALTER TABLE rental_contracts ADD COLUMN final_reading_date DATE;
ALTER TABLE rental_contracts ADD COLUMN completed_at TIMESTAMPTZ;

ALTER TABLE rental_contracts ADD CONSTRAINT contract_final_readings_valid CHECK (
    (status IN ('DRAFT', 'ACTIVE')
        AND final_mono_reading IS NULL AND final_color_reading IS NULL
        AND final_reading_date IS NULL AND completed_at IS NULL)
    OR
    (status = 'COMPLETED'
        AND final_mono_reading IS NOT NULL AND final_mono_reading >= initial_mono_reading
        AND final_color_reading IS NOT NULL AND final_color_reading >= initial_color_reading
        AND final_reading_date IS NOT NULL
        AND final_reading_date >= initial_reading_date AND final_reading_date <= end_date
        AND completed_at IS NOT NULL)
);
