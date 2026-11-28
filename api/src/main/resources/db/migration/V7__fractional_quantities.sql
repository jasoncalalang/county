-- Fractional quantities (ADR 0017): up to three decimals, above 0 and at most 9999. Existing whole
-- quantities convert exactly. Expand-first: the previous release still writes integers, which fit.
ALTER TABLE order_line
 DROP CONSTRAINT order_line_quantity_check,
 ALTER COLUMN quantity TYPE NUMERIC(7,3),
 ADD CONSTRAINT order_line_quantity_check CHECK (quantity > 0 AND quantity <= 9999);

ALTER TABLE quote_line
 DROP CONSTRAINT quote_line_quantity_check,
 ALTER COLUMN quantity TYPE NUMERIC(7,3),
 ADD CONSTRAINT quote_line_quantity_check CHECK (quantity > 0 AND quantity <= 9999);
