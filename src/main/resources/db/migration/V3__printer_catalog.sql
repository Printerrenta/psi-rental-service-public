CREATE TABLE printers (
    id UUID PRIMARY KEY,
    serial_number VARCHAR(100) NOT NULL UNIQUE,
    manufacturer VARCHAR(100) NOT NULL,
    model VARCHAR(120) NOT NULL,
    color_capable BOOLEAN NOT NULL,
    CONSTRAINT printer_serial_not_blank CHECK (length(trim(serial_number)) > 0),
    CONSTRAINT printer_manufacturer_not_blank CHECK (length(trim(manufacturer)) > 0),
    CONSTRAINT printer_model_not_blank CHECK (length(trim(model)) > 0)
);

-- resource_id is historical data and may not identify a known catalog printer.
ALTER TABLE rental_requests ADD COLUMN printer_id UUID REFERENCES printers(id);
CREATE INDEX rental_requests_printer_dates_idx ON rental_requests (printer_id, start_date, end_date);
