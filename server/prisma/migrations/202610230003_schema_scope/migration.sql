-- Trigger behavior must not depend on the caller's search_path (including restore tools).
ALTER FUNCTION whaletodo.assign_demo_slot() SET search_path = whaletodo, pg_temp;
ALTER TABLE sync_mutations ENABLE ROW LEVEL SECURITY;
CREATE POLICY owner_batch_scope ON sync_mutations TO whaletodo_app
 USING (EXISTS (SELECT 1 FROM sync_batches b WHERE b.id=sync_mutations.batch_id
  AND b.user_id=nullif(current_setting('app.user_id',true),'')::uuid))
 WITH CHECK (EXISTS (SELECT 1 FROM sync_batches b WHERE b.id=sync_mutations.batch_id
  AND b.user_id=nullif(current_setting('app.user_id',true),'')::uuid));
