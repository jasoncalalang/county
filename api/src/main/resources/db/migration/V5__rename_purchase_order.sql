-- ADR 0016: the order a reseller places with the wholesaler is a sales order. "Purchase order"
-- is kept for orders the business places with its suppliers. Names only; no data changes.
-- Constraint and sequence names match what CREATE TABLE sales_order would have produced.
ALTER TABLE purchase_order RENAME TO sales_order;
ALTER SEQUENCE purchase_order_id_seq RENAME TO sales_order_id_seq;

ALTER TABLE sales_order RENAME CONSTRAINT purchase_order_pkey TO sales_order_pkey;
ALTER TABLE sales_order RENAME CONSTRAINT purchase_order_status_check TO sales_order_status_check;
ALTER TABLE sales_order RENAME CONSTRAINT purchase_order_tenant_id_number_key
  TO sales_order_tenant_id_number_key;
ALTER TABLE sales_order RENAME CONSTRAINT purchase_order_tenant_id_idempotency_key_key
  TO sales_order_tenant_id_idempotency_key_key;
ALTER TABLE sales_order RENAME CONSTRAINT purchase_order_tenant_id_id_key
  TO sales_order_tenant_id_id_key;
ALTER TABLE sales_order RENAME CONSTRAINT purchase_order_tenant_id_fkey TO sales_order_tenant_id_fkey;
ALTER TABLE sales_order RENAME CONSTRAINT purchase_order_tenant_id_reseller_id_fkey
  TO sales_order_tenant_id_reseller_id_fkey;

ALTER INDEX order_delivery RENAME TO sales_order_delivery_idx;
ALTER INDEX reseller_recent RENAME TO sales_order_reseller_recent_idx;
