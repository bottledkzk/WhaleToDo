-- Additive only. Existing applied migrations are immutable. No API/payment activation.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';
CREATE INDEX idempotency_receipts_expiry ON idempotency_receipts(expires_at);
CREATE TABLE products (
 product_id varchar(32) PRIMARY KEY CHECK(product_id ~ '^[a-z0-9_]{1,32}$'),
 title varchar(64) NOT NULL CHECK(length(btrim(title))>0),
 price_minor integer NOT NULL CHECK(price_minor BETWEEN 0 AND 100000000),
 currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
 vip_days integer NOT NULL DEFAULT 0 CHECK(vip_days BETWEEN 0 AND 3660),
 points integer NOT NULL DEFAULT 0 CHECK(points BETWEEN 0 AND 1000000),
 active boolean NOT NULL DEFAULT true, created_at timestamptz(3) NOT NULL DEFAULT now()
);
CREATE TABLE orders (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
 product_id varchar(32) NOT NULL REFERENCES products(product_id) ON DELETE RESTRICT,
 amount_minor integer NOT NULL CHECK(amount_minor BETWEEN 0 AND 100000000),
 currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
 status varchar(16) NOT NULL CHECK(status IN ('PENDING','PAID','CLOSED','REFUNDED')),
 environment varchar(16) NOT NULL CHECK(environment IN ('mock','production')),
 product_snapshot jsonb NOT NULL,
 created_at timestamptz(3) NOT NULL DEFAULT now(), paid_at timestamptz(3),
 UNIQUE(user_id,id),
 CONSTRAINT orders_snapshot_check CHECK (
  jsonb_typeof(product_snapshot)='object' AND octet_length(product_snapshot::text)<=4096
  AND product_snapshot ?& ARRAY['title','price_minor','currency','points','vip_days']
  AND jsonb_typeof(product_snapshot->'title')='string'
  AND length(btrim(product_snapshot->>'title')) BETWEEN 1 AND 64
  AND product_snapshot->'price_minor'=to_jsonb(amount_minor)
  AND product_snapshot->>'currency'=currency
  AND jsonb_typeof(product_snapshot->'points')='number'
  AND (product_snapshot->>'points')::numeric BETWEEN 0 AND 1000000
  AND (product_snapshot->>'points')::numeric=trunc((product_snapshot->>'points')::numeric)
  AND jsonb_typeof(product_snapshot->'vip_days')='number'
  AND (product_snapshot->>'vip_days')::numeric BETWEEN 0 AND 3660
  AND (product_snapshot->>'vip_days')::numeric=trunc((product_snapshot->>'vip_days')::numeric)
 ),
 CHECK(status NOT IN ('PAID','REFUNDED') OR paid_at IS NOT NULL)
);
CREATE INDEX orders_owner_created ON orders(user_id,created_at DESC,id DESC);
CREATE TABLE payment_events (
 id uuid PRIMARY KEY, provider varchar(16) NOT NULL CHECK(provider ~ '^[a-z0-9_]{1,16}$'),
 event_id varchar(64) NOT NULL CHECK(length(event_id)>0),
 order_id uuid NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,
 status varchar(16) NOT NULL CHECK(status IN ('succeeded','failed','refunded')),
 amount_minor integer NOT NULL CHECK(amount_minor BETWEEN 0 AND 100000000),
 currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
 payload_hash char(64) NOT NULL CHECK(payload_hash ~ '^[a-f0-9]{64}$'),
 received_at timestamptz(3) NOT NULL DEFAULT now(), UNIQUE(provider,event_id)
);
CREATE INDEX payment_events_order ON payment_events(order_id);
-- Auditable amount/owner/environment/snapshot cannot change after order creation.
CREATE FUNCTION protect_order_snapshot() RETURNS trigger LANGUAGE plpgsql
 SET search_path=whaletodo,pg_temp AS $$
BEGIN
 IF ROW(NEW.id,NEW.user_id,NEW.product_id,NEW.amount_minor,NEW.currency,NEW.environment,NEW.product_snapshot,NEW.created_at)
 IS DISTINCT FROM ROW(OLD.id,OLD.user_id,OLD.product_id,OLD.amount_minor,OLD.currency,OLD.environment,OLD.product_snapshot,OLD.created_at)
 THEN RAISE EXCEPTION 'ORDER_SNAPSHOT_IMMUTABLE' USING ERRCODE='23514'; END IF;
 IF NEW.status<>OLD.status AND NOT (
  (OLD.status='PENDING' AND NEW.status IN ('PAID','CLOSED')) OR
  (OLD.status='PAID' AND NEW.status='REFUNDED'))
 THEN RAISE EXCEPTION 'ORDER_TRANSITION_INVALID' USING ERRCODE='23514'; END IF;
 IF OLD.paid_at IS NOT NULL AND NEW.paid_at IS DISTINCT FROM OLD.paid_at
 THEN RAISE EXCEPTION 'ORDER_PAID_TIME_IMMUTABLE' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER orders_immutable BEFORE UPDATE ON orders FOR EACH ROW EXECUTE FUNCTION protect_order_snapshot();
ALTER TABLE orders ENABLE ROW LEVEL SECURITY;
CREATE POLICY owner_scope ON orders TO whaletodo_app
 USING(user_id=nullif(current_setting('app.user_id',true),'')::uuid)
 WITH CHECK(user_id=nullif(current_setting('app.user_id',true),'')::uuid);
ALTER TABLE payment_events ENABLE ROW LEVEL SECURITY;
CREATE POLICY owner_order_scope ON payment_events TO whaletodo_app
 USING(EXISTS(SELECT 1 FROM orders o WHERE o.id=payment_events.order_id AND o.user_id=nullif(current_setting('app.user_id',true),'')::uuid))
 WITH CHECK(EXISTS(SELECT 1 FROM orders o WHERE o.id=payment_events.order_id AND o.user_id=nullif(current_setting('app.user_id',true),'')::uuid));
-- Null source_order_id remains compatible; a non-null source must belong to this owner.
ALTER TABLE entitlements ADD CONSTRAINT entitlements_order_owner_fkey
 FOREIGN KEY(user_id,source_order_id) REFERENCES orders(user_id,id) ON DELETE RESTRICT;
CREATE TABLE email_verifications (
 id uuid PRIMARY KEY,
 purpose varchar(16) NOT NULL CHECK(purpose IN ('register','reset','bind')),
 email_normalized varchar(254) NOT NULL CHECK(email_normalized=lower(btrim(email_normalized)) AND length(email_normalized)>3),
 code_hmac char(64) NOT NULL CHECK(code_hmac ~ '^[a-f0-9]{64}$'),
 attempts smallint NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 5),
 expires_at timestamptz(3) NOT NULL, consumed_at timestamptz(3),
 created_at timestamptz(3) NOT NULL DEFAULT now(), CHECK(expires_at>created_at)
);
CREATE UNIQUE INDEX email_verifications_unconsumed ON email_verifications(email_normalized,purpose) WHERE consumed_at IS NULL;
CREATE INDEX email_verifications_lookup ON email_verifications(email_normalized,purpose,created_at DESC);
CREATE INDEX email_verifications_expiry ON email_verifications(expires_at);
CREATE TABLE email_throttle (
 scope varchar(8) NOT NULL CHECK(scope IN ('email','ip','account','global')),
 scope_key varchar(128) NOT NULL,
 window_start timestamptz(3) NOT NULL,
 count integer NOT NULL DEFAULT 0 CHECK(count BETWEEN 0 AND 100000000),
 updated_at timestamptz(3) NOT NULL DEFAULT now(), PRIMARY KEY(scope,scope_key,window_start),
 CHECK((scope='global' AND scope_key='all') OR (scope<>'global' AND scope_key ~ '^[a-f0-9]{64}$'))
);
CREATE INDEX email_throttle_expiry ON email_throttle(window_start);
CREATE TABLE email_verify_tickets (
 id uuid PRIMARY KEY, token_hmac char(64) NOT NULL UNIQUE CHECK(token_hmac ~ '^[a-f0-9]{64}$'),
 purpose varchar(16) NOT NULL CHECK(purpose IN ('register','reset','bind')),
 email_normalized varchar(254) NOT NULL CHECK(email_normalized=lower(btrim(email_normalized)) AND length(email_normalized)>3),
 expires_at timestamptz(3) NOT NULL, consumed_at timestamptz(3),
 created_at timestamptz(3) NOT NULL DEFAULT now(), CHECK(expires_at>created_at)
);
CREATE INDEX email_verify_tickets_expiry ON email_verify_tickets(expires_at);
COMMIT;
