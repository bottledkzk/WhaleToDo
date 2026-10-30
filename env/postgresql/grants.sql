-- Run as postgres after migrations. App cannot grant itself admin or modify schema.
GRANT USAGE ON SCHEMA whaletodo TO whaletodo_app,whaletodo_ai;
-- Explicit allowlist: future migrations never grant new sensitive tables implicitly.
GRANT SELECT ON whaletodo.users,whaletodo.auth_sessions,whaletodo.idempotency_receipts,
 whaletodo.user_settings,whaletodo.tasks,whaletodo.plan_blocks,whaletodo.checkins,
 whaletodo.availability,whaletodo.sync_batches,whaletodo.sync_mutations,whaletodo.sync_changes,
 whaletodo.daily_claims,whaletodo.point_wallets,whaletodo.point_ledger,whaletodo.entitlements,
 whaletodo.ai_requests TO whaletodo_app;
REVOKE ALL ON whaletodo._prisma_migrations FROM whaletodo_app;
GRANT INSERT(id,username,email,password_hash,display_name,created_at,updated_at),
 UPDATE(email,password_hash,display_name,updated_at), DELETE ON whaletodo.users TO whaletodo_app;
GRANT INSERT,UPDATE,DELETE ON whaletodo.auth_sessions,whaletodo.idempotency_receipts,
 whaletodo.user_settings,whaletodo.tasks,whaletodo.plan_blocks,whaletodo.checkins,whaletodo.availability,
 whaletodo.sync_batches,whaletodo.sync_mutations,whaletodo.sync_changes,whaletodo.daily_claims,
 whaletodo.point_wallets,whaletodo.entitlements TO whaletodo_app;
GRANT INSERT ON whaletodo.point_ledger TO whaletodo_app;
GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA whaletodo TO whaletodo_app;
GRANT SELECT(id,status) ON whaletodo.users TO whaletodo_ai;
GRANT SELECT(user_id,access_hash,revoked_at,access_expires_at) ON whaletodo.auth_sessions TO whaletodo_ai;
GRANT SELECT,UPDATE ON whaletodo.point_wallets TO whaletodo_ai;
GRANT INSERT ON whaletodo.point_ledger TO whaletodo_ai;
GRANT SELECT,INSERT,UPDATE ON whaletodo.ai_requests TO whaletodo_ai;

GRANT SELECT(user_id,kind,expires_at) ON whaletodo.entitlements TO whaletodo_ai;

-- New internal tables: no credentials or delete rights for AI/runtime maintenance.
GRANT SELECT ON whaletodo.products,whaletodo.orders,whaletodo.payment_events,
 whaletodo.email_verifications,whaletodo.email_throttle,whaletodo.email_verify_tickets TO whaletodo_app;
GRANT INSERT ON whaletodo.orders,whaletodo.payment_events TO whaletodo_app;
GRANT UPDATE(status,paid_at) ON whaletodo.orders TO whaletodo_app;
GRANT INSERT,UPDATE ON whaletodo.email_verifications,whaletodo.email_throttle,
 whaletodo.email_verify_tickets TO whaletodo_app;
REVOKE DELETE ON whaletodo.orders,whaletodo.payment_events,whaletodo.email_verifications,
 whaletodo.email_throttle,whaletodo.email_verify_tickets FROM whaletodo_app;
REVOKE CREATE ON SCHEMA whaletodo FROM PUBLIC,whaletodo_app,whaletodo_ai;
GRANT SELECT,INSERT,UPDATE ON whaletodo.account_documents TO whaletodo_app;
REVOKE ALL ON whaletodo.account_documents FROM whaletodo_ai;
