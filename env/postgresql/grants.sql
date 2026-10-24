-- Run as postgres after migrations. App cannot grant itself admin or modify schema.
GRANT USAGE ON SCHEMA whaletodo TO whaletodo_app,whaletodo_ai;
GRANT SELECT ON ALL TABLES IN SCHEMA whaletodo TO whaletodo_app;
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
