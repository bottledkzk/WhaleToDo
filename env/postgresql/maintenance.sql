-- Scheduled administrator task. Bounded batches; no ledger, session-family,
-- sync-tombstone, order, account, task, or unresolved AI-request deletion.
BEGIN;
SET LOCAL lock_timeout='2s';
SET LOCAL statement_timeout='10s';
SET LOCAL search_path=whaletodo,pg_catalog;
DO $$
DECLARE n integer;
BEGIN
 DELETE FROM idempotency_receipts WHERE ctid IN (
  SELECT ctid FROM idempotency_receipts WHERE expires_at<now()-interval '7 days' ORDER BY expires_at LIMIT 1000 FOR UPDATE SKIP LOCKED);
 GET DIAGNOSTICS n=ROW_COUNT; RAISE NOTICE 'expired_receipts_deleted=%',n;
 IF to_regclass('whaletodo.email_verifications') IS NOT NULL THEN
  DELETE FROM email_verifications WHERE ctid IN (
   SELECT ctid FROM email_verifications WHERE expires_at<now()-interval '24 hours' ORDER BY expires_at LIMIT 1000 FOR UPDATE SKIP LOCKED);
  GET DIAGNOSTICS n=ROW_COUNT; RAISE NOTICE 'expired_codes_deleted=%',n;
  DELETE FROM email_verify_tickets WHERE ctid IN (
   SELECT ctid FROM email_verify_tickets WHERE expires_at<now()-interval '24 hours' ORDER BY expires_at LIMIT 1000 FOR UPDATE SKIP LOCKED);
  GET DIAGNOSTICS n=ROW_COUNT; RAISE NOTICE 'expired_tickets_deleted=%',n;
  DELETE FROM email_throttle WHERE ctid IN (
   SELECT ctid FROM email_throttle WHERE window_start<now()-interval '48 hours' ORDER BY window_start LIMIT 1000 FOR UPDATE SKIP LOCKED);
  GET DIAGNOSTICS n=ROW_COUNT; RAISE NOTICE 'expired_throttle_deleted=%',n;
 END IF;
END $$;
COMMIT;
