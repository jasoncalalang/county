-- Fractional quantities (ADR 0017): up to three decimals, above 0 and at most 9999. Existing whole
-- quantities convert exactly. The previous release can still write integers, which fit, but it reads
-- a fractional row truncated (1.5 kg as 1): deploy this with the release that reads decimals.
ALTER TABLE order_line
 DROP CONSTRAINT order_line_quantity_check,
 ALTER COLUMN quantity TYPE NUMERIC(7,3),
 ADD CONSTRAINT order_line_quantity_check CHECK (quantity > 0 AND quantity <= 9999);

ALTER TABLE quote_line
 DROP CONSTRAINT quote_line_quantity_check,
 ALTER COLUMN quantity TYPE NUMERIC(7,3),
 ADD CONSTRAINT quote_line_quantity_check CHECK (quantity > 0 AND quantity <= 9999);
