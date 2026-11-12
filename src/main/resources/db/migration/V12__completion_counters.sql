-- Historical completions remain unchanged; new completions retain all four accepted counters.
ALTER TABLE rental_contracts ADD COLUMN final_counters JSONB;
ALTER TABLE rental_contracts ADD CONSTRAINT final_counters_shape
  CHECK (final_counters IS NULL OR (jsonb_typeof(final_counters)='array' AND jsonb_array_length(final_counters)=4));
