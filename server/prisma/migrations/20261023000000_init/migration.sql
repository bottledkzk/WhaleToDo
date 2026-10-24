-- WhaleToDo 初始迁移（建议稿，PostgreSQL 16）
-- 与 prisma/schema.prisma 对应；由 Bottledkzk 侧统一维护后合入正式迁移目录。
-- 要点：金额为最小货币单位整数；时间为 timestamptz(3)；令牌只存 SHA-256 摘要；
--      账本 event_id 幂等唯一、request_id 仅索引；钱包余额有 CHECK 约束。

BEGIN;

-- ==================== 账户 ====================
CREATE TABLE users (
    id            uuid         PRIMARY KEY,
    username      varchar(32)  NOT NULL,
    password_hash varchar(255) NOT NULL,
    display_name  varchar(32)  NOT NULL,
    status        varchar(16)  NOT NULL DEFAULT 'active',
    created_at    timestamptz(3) NOT NULL DEFAULT now(),
    updated_at    timestamptz(3) NOT NULL DEFAULT now(),
    CONSTRAINT users_username_key UNIQUE (username),
    CONSTRAINT users_username_len CHECK (char_length(username) BETWEEN 3 AND 32),
    CONSTRAINT users_status_chk   CHECK (status IN ('active', 'disabled'))
);

CREATE TABLE auth_sessions (
    id                 uuid        PRIMARY KEY,
    user_id            uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    access_hash        char(64)    NOT NULL,
    refresh_hash       char(64)    NOT NULL,
    family_id          uuid        NOT NULL,
    access_expires_at  timestamptz(3) NOT NULL,
    refresh_expires_at timestamptz(3) NOT NULL,
    revoked_at         timestamptz(3),
    device_id          varchar(64),
    created_at         timestamptz(3) NOT NULL DEFAULT now(),
    CONSTRAINT auth_sessions_expiry_chk CHECK (refresh_expires_at > access_expires_at)
);
CREATE INDEX auth_sessions_user_revoked_idx ON auth_sessions (user_id, revoked_at);
CREATE INDEX auth_sessions_access_hash_idx  ON auth_sessions (access_hash);
CREATE INDEX auth_sessions_refresh_hash_idx ON auth_sessions (refresh_hash);
CREATE INDEX auth_sessions_family_idx       ON auth_sessions (family_id);

-- ==================== 签到与积分账本 ====================
CREATE TABLE daily_claims (
    id            uuid        PRIMARY KEY,
    user_id       uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    business_date date        NOT NULL,
    reward_type   varchar(24) NOT NULL,
    points        int         NOT NULL,
    created_at    timestamptz(3) NOT NULL DEFAULT now(),
    CONSTRAINT daily_claims_user_date_type_key UNIQUE (user_id, business_date, reward_type),
    CONSTRAINT daily_claims_points_chk CHECK (points > 0)
);

CREATE TABLE point_wallets (
    user_id    uuid PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    available  int  NOT NULL DEFAULT 0,
    reserved   int  NOT NULL DEFAULT 0,
    updated_at timestamptz(3) NOT NULL DEFAULT now(),
    CONSTRAINT point_wallets_available_nonneg CHECK (available >= 0),
    CONSTRAINT point_wallets_reserved_nonneg  CHECK (reserved >= 0)
);

CREATE TABLE point_ledger (
    id             uuid        PRIMARY KEY,
    user_id        uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    event_id       varchar(64) NOT NULL,
    type           varchar(16) NOT NULL,
    delta          int         NOT NULL,
    balance_after  int         NOT NULL,
    reserved_after int         NOT NULL,
    request_id     varchar(64),
    reason         varchar(120),
    created_at     timestamptz(3) NOT NULL DEFAULT now(),
    -- 幂等键：同一用户同一事件只能记一次
    CONSTRAINT point_ledger_user_event_key UNIQUE (user_id, event_id),
    CONSTRAINT point_ledger_type_chk CHECK (type IN ('award', 'reserve', 'settle', 'release', 'adjust')),
    CONSTRAINT point_ledger_balances_nonneg CHECK (balance_after >= 0 AND reserved_after >= 0)
);
CREATE INDEX point_ledger_user_created_idx ON point_ledger (user_id, created_at DESC);
-- 注意：request_id 只做关联查询，**不建唯一索引**（一个请求可能产生多条账本事件）
CREATE INDEX point_ledger_request_idx ON point_ledger (request_id);

-- ==================== 商品、订单与支付 ====================
CREATE TABLE products (
    product_id  varchar(32) PRIMARY KEY,
    title       varchar(64) NOT NULL,
    price_minor int         NOT NULL,
    currency    char(3)     NOT NULL,
    vip_days    int         NOT NULL DEFAULT 0,
    points      int         NOT NULL DEFAULT 0,
    active      boolean     NOT NULL DEFAULT true,
    CONSTRAINT products_price_chk CHECK (price_minor >= 0),
    CONSTRAINT products_currency_chk CHECK (currency = upper(currency))
);

CREATE TABLE orders (
    id              uuid        PRIMARY KEY,
    user_id         uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    product_id      varchar(32) NOT NULL REFERENCES products (product_id),
    amount_minor    int         NOT NULL,
    currency        char(3)     NOT NULL,
    status          varchar(16) NOT NULL,
    idempotency_key varchar(64) NOT NULL,
    environment     varchar(16) NOT NULL DEFAULT 'production',
    created_at      timestamptz(3) NOT NULL DEFAULT now(),
    paid_at         timestamptz(3),
    CONSTRAINT orders_user_idem_key UNIQUE (user_id, idempotency_key),
    CONSTRAINT orders_status_chk CHECK (status IN ('PENDING', 'PAID', 'CLOSED', 'REFUNDED')),
    CONSTRAINT orders_env_chk CHECK (environment IN ('mock', 'production')),
    CONSTRAINT orders_amount_chk CHECK (amount_minor >= 0)
);
CREATE INDEX orders_user_created_idx ON orders (user_id, created_at DESC);

CREATE TABLE payment_events (
    id           uuid        PRIMARY KEY,
    provider     varchar(16) NOT NULL,
    event_id     varchar(64) NOT NULL,
    order_id     uuid        NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    status       varchar(16) NOT NULL,
    amount_minor int         NOT NULL,
    payload_hash char(64)    NOT NULL,
    received_at  timestamptz(3) NOT NULL DEFAULT now(),
    CONSTRAINT payment_events_provider_event_key UNIQUE (provider, event_id),
    CONSTRAINT payment_events_status_chk CHECK (status IN ('succeeded', 'failed', 'refunded'))
);
CREATE INDEX payment_events_order_idx ON payment_events (order_id);

CREATE TABLE entitlements (
    id              uuid        PRIMARY KEY,
    user_id         uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kind            varchar(16) NOT NULL,
    expires_at      timestamptz(3) NOT NULL,
    source_order_id uuid REFERENCES orders (id),
    created_at      timestamptz(3) NOT NULL DEFAULT now(),
    CONSTRAINT entitlements_kind_chk CHECK (kind IN ('vip'))
);
CREATE INDEX entitlements_user_kind_expiry_idx ON entitlements (user_id, kind, expires_at DESC);

-- ==================== 同步 ====================
CREATE TABLE sync_batches (
    id         uuid        PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    batch_id   varchar(64) NOT NULL,
    device_id  varchar(64) NOT NULL,
    status     varchar(16) NOT NULL,
    created_at timestamptz(3) NOT NULL DEFAULT now(),
    CONSTRAINT sync_batches_user_batch_key UNIQUE (user_id, batch_id),
    CONSTRAINT sync_batches_status_chk CHECK (status IN ('applied', 'partial', 'rejected'))
);

CREATE TABLE sync_mutations (
    id             uuid        PRIMARY KEY,
    batch_id       uuid        NOT NULL REFERENCES sync_batches (id) ON DELETE CASCADE,
    object_type    varchar(24) NOT NULL,
    object_id      varchar(64) NOT NULL,
    op             varchar(8)  NOT NULL,
    server_version int         NOT NULL,
    result         varchar(16) NOT NULL,
    created_at     timestamptz(3) NOT NULL DEFAULT now(),
    CONSTRAINT sync_mutations_op_chk CHECK (op IN ('create', 'update', 'delete')),
    CONSTRAINT sync_mutations_result_chk CHECK (result IN ('applied', 'conflict', 'rejected')),
    CONSTRAINT sync_mutations_object_type_chk
        CHECK (object_type IN ('task', 'block', 'checkin', 'availability'))
);
CREATE INDEX sync_mutations_batch_idx  ON sync_mutations (batch_id);
CREATE INDEX sync_mutations_object_idx ON sync_mutations (object_type, object_id);

CREATE TABLE sync_cursors (
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    device_id  varchar(64) NOT NULL,
    last_seq   bigint      NOT NULL DEFAULT 0,
    updated_at timestamptz(3) NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, device_id),
    CONSTRAINT sync_cursors_last_seq_nonneg CHECK (last_seq >= 0)
);

COMMIT;
