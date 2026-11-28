-- Reseller links expire (ADR 0018): 90 days after the token was issued. The default backfills
-- existing rows to 90 days from this migration and keeps inserts by the previous release working.
ALTER TABLE reseller ADD COLUMN token_expires_at TIMESTAMPTZ NOT NULL DEFAULT (now() + interval '90 days');
