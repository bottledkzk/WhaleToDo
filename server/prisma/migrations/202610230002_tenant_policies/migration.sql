-- App-owned data requires SET LOCAL app.user_id inside the request transaction.
DO $$
DECLARE t text;
BEGIN
 FOREACH t IN ARRAY ARRAY['tasks','plan_blocks','checkins','availability','user_settings','sync_batches','sync_changes'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
  EXECUTE format($policy$CREATE POLICY owner_scope ON %I TO whaletodo_app
   USING (user_id = nullif(current_setting('app.user_id',true),'')::uuid)
   WITH CHECK (user_id = nullif(current_setting('app.user_id',true),'')::uuid)$policy$,t);
 END LOOP;
END $$;
