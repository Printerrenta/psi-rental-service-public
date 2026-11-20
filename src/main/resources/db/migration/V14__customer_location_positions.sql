-- Map pin for a customer location. Both coordinates are set together or both are empty
-- (an address that has not been geocoded yet, or whose address changed since geocoding).
ALTER TABLE customer_locations
    ADD COLUMN latitude NUMERIC(8, 6),
    ADD COLUMN longitude NUMERIC(9, 6),
    ADD CONSTRAINT customer_locations_position_chk CHECK (
        (latitude IS NULL) = (longitude IS NULL)
        AND (latitude IS NULL OR (latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180))
    );
