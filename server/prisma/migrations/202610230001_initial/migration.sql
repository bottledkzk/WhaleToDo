-- Small course-demo database. Passwords and session tokens MUST be hashed by account service.
-- The migration runs as the schema owner; runtime roles have no DDL.
CREATE TABLE users (
 id uuid PRIMARY KEY,
 demo_slot integer UNIQUE NOT NULL CHECK (demo_slot BETWEEN 1 AND 10),
 username varchar(32) NOT NULL CHECK (username ~ '^[A-Za-z0-9_]{3,32}$'),
 email varchar(254), email_verified_at timestamptz(3),
 password_hash varchar(512) NOT NULL CHECK (password_hash ~ '^(scrypt\$|\$argon2id\$)'),
 display_name varchar(64) NOT NULL,
 role varchar(16) NOT NULL DEFAULT 'user' CHECK (role IN ('user','admin')),
 status varchar(16) NOT NULL DEFAULT 'active' CHECK (status IN ('active','disabled')),
 created_at timestamptz(3) NOT NULL DEFAULT now(), updated_at timestamptz(3) NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX users_username_ci ON users(lower(username));
CREATE UNIQUE INDEX users_email_ci ON users(lower(email)) WHERE email IS NOT NULL;
CREATE FUNCTION assign_demo_slot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM pg_advisory_xact_lock(27102310);
 IF NEW.demo_slot IS NULL THEN
  SELECT s INTO NEW.demo_slot FROM generate_series(1,10) AS s WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.demo_slot=s) ORDER BY s LIMIT 1;
 END IF;
 IF NEW.demo_slot IS NULL THEN RAISE EXCEPTION 'DEMO_USER_LIMIT' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER users_demo_capacity BEFORE INSERT ON users FOR EACH ROW EXECUTE FUNCTION assign_demo_slot();
CREATE TABLE auth_sessions (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 access_hash char(64) NOT NULL UNIQUE CHECK(access_hash ~ '^[a-f0-9]{64}$'),
 refresh_hash char(64) NOT NULL UNIQUE CHECK(refresh_hash ~ '^[a-f0-9]{64}$'),
 family_id uuid NOT NULL, access_expires_at timestamptz(3) NOT NULL,
 refresh_expires_at timestamptz(3) NOT NULL, revoked_at timestamptz(3),
 device_id varchar(128), created_at timestamptz(3) NOT NULL DEFAULT now()
);
CREATE INDEX auth_sessions_family ON auth_sessions(family_id);
CREATE INDEX auth_sessions_user ON auth_sessions(user_id);
CREATE TABLE idempotency_receipts (
 scope varchar(80) NOT NULL, key varchar(128) NOT NULL,
 fingerprint char(64) NOT NULL, response jsonb NOT NULL, expires_at timestamptz(3) NOT NULL,
 PRIMARY KEY(scope,key), CHECK(octet_length(response::text)<=65536)
);
CREATE TABLE user_settings (
 user_id uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
 settings jsonb NOT NULL DEFAULT '{}', version integer NOT NULL DEFAULT 1 CHECK(version>0),
 updated_at timestamptz(3) NOT NULL DEFAULT now(), CHECK(octet_length(settings::text)<=32768)
);
CREATE TABLE tasks (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 title varchar(160) NOT NULL, note varchar(4096) NOT NULL DEFAULT '',
 deadline timestamptz(3), earliest_start timestamptz(3), estimate_minutes integer CHECK(estimate_minutes BETWEEN 1 AND 525600),
 importance numeric(2,1) CHECK(importance BETWEEN 0.5 AND 5),
 status varchar(16) NOT NULL DEFAULT 'inbox' CHECK(status IN ('inbox','todo','done','archived')),
 version integer NOT NULL DEFAULT 1 CHECK(version>0), deleted_at timestamptz(3),
 created_at timestamptz(3) NOT NULL DEFAULT now(), updated_at timestamptz(3) NOT NULL DEFAULT now(), UNIQUE(user_id,id)
);
CREATE INDEX tasks_owner_updated ON tasks(user_id,updated_at,id);
CREATE TABLE plan_blocks (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 task_id uuid NOT NULL, starts_at timestamptz(3) NOT NULL, ends_at timestamptz(3) NOT NULL,
 locked boolean NOT NULL DEFAULT false, completed boolean NOT NULL DEFAULT false,
 version integer NOT NULL DEFAULT 1 CHECK(version>0), deleted_at timestamptz(3),
 updated_at timestamptz(3) NOT NULL DEFAULT now(), UNIQUE(user_id,id), CHECK(ends_at>starts_at),
 FOREIGN KEY(user_id,task_id) REFERENCES tasks(user_id,id) ON DELETE CASCADE
);
CREATE INDEX plan_blocks_owner_start ON plan_blocks(user_id,starts_at);
CREATE TABLE checkins (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 task_id uuid, completed_at timestamptz(3) NOT NULL, actual_minutes integer CHECK(actual_minutes BETWEEN 0 AND 525600),
 rating integer CHECK(rating BETWEEN 1 AND 5), reflection varchar(4096) NOT NULL DEFAULT '',
 version integer NOT NULL DEFAULT 1 CHECK(version>0), deleted_at timestamptz(3), updated_at timestamptz(3) NOT NULL DEFAULT now(),
 FOREIGN KEY(user_id,task_id) REFERENCES tasks(user_id,id)
);
CREATE TABLE availability (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 local_date date, weekday integer CHECK(weekday BETWEEN 1 AND 7),
 start_minute integer NOT NULL CHECK(start_minute BETWEEN 0 AND 1439),
 end_minute integer NOT NULL CHECK(end_minute BETWEEN 1 AND 1440), timezone varchar(64) NOT NULL,
 version integer NOT NULL DEFAULT 1 CHECK(version>0), deleted_at timestamptz(3), updated_at timestamptz(3) NOT NULL DEFAULT now(),
 CHECK(end_minute>start_minute), CHECK((local_date IS NULL) <> (weekday IS NULL))
);
CREATE TABLE sync_batches (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 batch_id uuid NOT NULL, device_id varchar(128) NOT NULL, fingerprint char(64) NOT NULL,
 status varchar(16) NOT NULL, response jsonb, created_at timestamptz(3) NOT NULL DEFAULT now(), UNIQUE(user_id,batch_id)
);
CREATE TABLE sync_mutations (
 id uuid PRIMARY KEY, batch_id uuid NOT NULL REFERENCES sync_batches(id) ON DELETE CASCADE,
 mutation_id uuid NOT NULL, object_type varchar(24) NOT NULL CHECK(object_type IN ('task','block','checkin','availability','settings')),
 object_id uuid NOT NULL, op varchar(8) NOT NULL CHECK(op IN ('create','update','delete')),
 result varchar(16) NOT NULL CHECK(result IN ('applied','conflict','rejected')), server_version integer, UNIQUE(batch_id,mutation_id)
);
CREATE TABLE sync_changes (
 seq bigserial PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 object_type varchar(24) NOT NULL, object_id uuid NOT NULL, version integer NOT NULL,
 deleted boolean NOT NULL DEFAULT false, payload jsonb NOT NULL, created_at timestamptz(3) NOT NULL DEFAULT now(), CHECK(octet_length(payload::text)<=65536)
);
CREATE INDEX sync_changes_owner_seq ON sync_changes(user_id,seq);
CREATE TABLE daily_claims (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 business_date date NOT NULL, reward_type varchar(24) NOT NULL, points integer NOT NULL CHECK(points>0),
 created_at timestamptz(3) NOT NULL DEFAULT now(), UNIQUE(user_id,business_date,reward_type)
);
CREATE TABLE point_wallets (
 user_id uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
 available integer NOT NULL DEFAULT 0 CHECK(available BETWEEN 0 AND 1000000),
 reserved integer NOT NULL DEFAULT 0 CHECK(reserved BETWEEN 0 AND 1000000),
 updated_at timestamptz(3) NOT NULL DEFAULT now(), CHECK(available+reserved<=1000000)
);
CREATE TABLE point_ledger (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 event_id varchar(160) NOT NULL, request_id uuid,
 type varchar(16) NOT NULL CHECK(type IN ('award','reserve','settle','release','adjust')),
 available_delta integer NOT NULL, reserved_delta integer NOT NULL,
 balance_after integer NOT NULL CHECK(balance_after>=0), reserved_after integer NOT NULL CHECK(reserved_after>=0),
 reason varchar(160) NOT NULL, created_at timestamptz(3) NOT NULL DEFAULT now(), UNIQUE(user_id,event_id)
);
CREATE INDEX point_ledger_request ON point_ledger(request_id);
CREATE TABLE entitlements (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 kind varchar(32) NOT NULL, expires_at timestamptz(3) NOT NULL, source_order_id uuid,
 created_at timestamptz(3) NOT NULL DEFAULT now()
);
CREATE TABLE ai_requests (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 client_request_id uuid NOT NULL, fingerprint char(64) NOT NULL,
 mode varchar(24) NOT NULL CHECK(mode IN ('chat','plan','review','diary','share_copy')),
 model_code varchar(64) NOT NULL, status varchar(16) NOT NULL CHECK(status IN ('reserved','running','succeeded','failed','unknown')),
 points integer NOT NULL CHECK(points BETWEEN 1 AND 1000),
 budget_tokens integer NOT NULL CHECK(budget_tokens>0), usage_tokens integer CHECK(usage_tokens>=0),
 response_text text, error_code varchar(32), created_at timestamptz(3) NOT NULL DEFAULT now(),
 updated_at timestamptz(3) NOT NULL DEFAULT now(), UNIQUE(user_id,client_request_id), CHECK(octet_length(response_text)<=65536)
);
CREATE INDEX ai_requests_day ON ai_requests(created_at,user_id);
