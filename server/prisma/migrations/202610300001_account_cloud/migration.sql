-- Explicit schedule backups and account-following profile content have separate documents.
CREATE TABLE account_documents (
 user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 kind varchar(16) NOT NULL CHECK (kind IN ('schedule','profile')),
 revision integer NOT NULL DEFAULT 1 CHECK (revision > 0),
 payload jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object' AND octet_length(payload::text) <= 2097152),
 updated_at timestamptz(3) NOT NULL DEFAULT now(),
 PRIMARY KEY (user_id,kind)
);
ALTER TABLE account_documents ENABLE ROW LEVEL SECURITY;
CREATE POLICY owner_scope ON account_documents TO whaletodo_app
 USING (user_id = nullif(current_setting('app.user_id',true),'')::uuid)
 WITH CHECK (user_id = nullif(current_setting('app.user_id',true),'')::uuid);
GRANT SELECT,INSERT,UPDATE ON account_documents TO whaletodo_app;
REVOKE ALL ON account_documents FROM whaletodo_ai;
