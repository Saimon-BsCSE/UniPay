-- UniPay — H2 in-memory schema (MySQL compatibility mode) used for the
-- zero-setup demo profile. Mirrors db/schema-mysql.sql; ENUM types are
-- expressed as VARCHAR + CHECK because H2 does not implement MySQL ENUM.

CREATE TABLE IF NOT EXISTS users (
    user_id       VARCHAR(50)  PRIMARY KEY,
    full_name     VARCHAR(100) NOT NULL,
    phone_number  VARCHAR(15)  UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    CHECK (role IN ('STUDENT','FACULTY','STAFF','VENDOR')),
    avatar_url    CLOB,
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS wallets (
    wallet_id       INT AUTO_INCREMENT PRIMARY KEY,
    user_id         VARCHAR(50) UNIQUE NOT NULL,
    current_balance DECIMAL(10, 2) DEFAULT 0.00,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(user_id)
);

CREATE TABLE IF NOT EXISTS vendor_profiles (
    vendor_id          VARCHAR(50) PRIMARY KEY,
    stall_name         VARCHAR(100) NOT NULL,
    stall_category     VARCHAR(20)  NOT NULL,
    CHECK (stall_category IN ('CANTEEN','BOOKSHOP','FOOD_STALL','OTHER')),
    qr_code_identifier VARCHAR(255) UNIQUE NOT NULL,
    FOREIGN KEY (vendor_id) REFERENCES users(user_id)
);

CREATE TABLE IF NOT EXISTS transactions (
    transaction_id   VARCHAR(100) PRIMARY KEY,
    sender_id        VARCHAR(50) NOT NULL,
    receiver_id      VARCHAR(50) NOT NULL,
    amount           DECIMAL(10, 2) NOT NULL,
    transaction_type VARCHAR(30)  NOT NULL,
    CHECK (transaction_type IN ('MFS_CASH_IN','VENDOR_PAYMENT','P2P_TRANSFER','SPLIT_PAY','VENDOR_CASHOUT','LOYALTY_REDEMPTION')),
    timestamp        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (sender_id) REFERENCES users(user_id),
    FOREIGN KEY (receiver_id) REFERENCES users(user_id)
);

CREATE INDEX IF NOT EXISTS idx_txn_sender   ON transactions (sender_id);
CREATE INDEX IF NOT EXISTS idx_txn_receiver ON transactions (receiver_id);
CREATE INDEX IF NOT EXISTS idx_txn_time     ON transactions (timestamp);

CREATE TABLE IF NOT EXISTS split_bills (
    bill_id          VARCHAR(50) PRIMARY KEY,
    creator_id       VARCHAR(50) NOT NULL,
    title            VARCHAR(100) NOT NULL,
    total_amount     DECIMAL(10, 2) NOT NULL,
    split_type       VARCHAR(20) NOT NULL,
    CHECK (split_type IN ('EVEN','CUSTOM')),
    status           VARCHAR(20) NOT NULL,
    CHECK (status IN ('ACTIVE','SETTLED','CANCELLED')),
    note             VARCHAR(255),
    created_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (creator_id) REFERENCES users(user_id)
);

CREATE TABLE IF NOT EXISTS split_requests (
    request_id       VARCHAR(50) PRIMARY KEY,
    bill_id          VARCHAR(50) NOT NULL,
    participant_id   VARCHAR(50) NOT NULL,
    amount           DECIMAL(10, 2) NOT NULL,
    status           VARCHAR(20) NOT NULL,
    CHECK (status IN ('PENDING','ACCEPTED','DECLINED','CANCELLED')),
    transaction_id   VARCHAR(100),
    paid_at          TIMESTAMP NULL,
    created_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (bill_id) REFERENCES split_bills(bill_id) ON DELETE CASCADE,
    FOREIGN KEY (participant_id) REFERENCES users(user_id)
);

CREATE INDEX IF NOT EXISTS idx_split_creator   ON split_bills (creator_id);
CREATE INDEX IF NOT EXISTS idx_req_participant ON split_requests (participant_id);
CREATE INDEX IF NOT EXISTS idx_req_bill        ON split_requests (bill_id);

CREATE TABLE IF NOT EXISTS vendor_cashouts (
    cashout_id          VARCHAR(50) PRIMARY KEY,
    vendor_id           VARCHAR(50) NOT NULL,
    amount              DECIMAL(10, 2) NOT NULL,
    channel             VARCHAR(20) NOT NULL,
    CHECK (channel IN ('BKASH','NAGAD','ROCKET','BANK')),
    account_number      VARCHAR(50) NOT NULL,
    account_holder_name VARCHAR(100),
    bank_name           VARCHAR(100),
    branch_name         VARCHAR(100),
    transaction_id      VARCHAR(100) NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
    CHECK (status IN ('COMPLETED','PROCESSING')),
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (vendor_id) REFERENCES users(user_id)
);

CREATE INDEX IF NOT EXISTS idx_cashout_vendor ON vendor_cashouts (vendor_id);
CREATE INDEX IF NOT EXISTS idx_cashout_time   ON vendor_cashouts (created_at);

-- ── OTP Records (hashed, TTL enforced via expires_at) ────────────────────────
CREATE TABLE IF NOT EXISTS otp_records (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id      VARCHAR(50)   NOT NULL,
    provider     VARCHAR(10)   NOT NULL,
    phone_number VARCHAR(15)   NOT NULL,
    otp_hash     VARCHAR(255)  NOT NULL,
    amount       DECIMAL(10,2) NOT NULL,
    nonce        VARCHAR(100)  NOT NULL,
    attempts     INT DEFAULT 0,
    max_attempts INT DEFAULT 3,
    expires_at   TIMESTAMP     NOT NULL,
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(user_id)
);

CREATE INDEX IF NOT EXISTS idx_otp_user ON otp_records (user_id);

-- ── Loyalty Points (one row per user, running total) ─────────────────────────
CREATE TABLE IF NOT EXISTS loyalty_points (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id      VARCHAR(50)    NOT NULL UNIQUE,
    total_points DECIMAL(12,4)  NOT NULL DEFAULT 0.0000,
    FOREIGN KEY (user_id) REFERENCES users(user_id)
);

CREATE INDEX IF NOT EXISTS idx_loyalty_user ON loyalty_points (user_id);

-- ── bKash Pending Payments (real tokenized checkout sessions) ────────────────
-- Tracks in-flight sessions: PENDING → COMPLETED | FAILED
-- Inserted on createPayment, updated in the callback after executePayment.
CREATE TABLE IF NOT EXISTS bkash_pending_payments (
    payment_id  VARCHAR(100)  PRIMARY KEY,              -- bKash paymentID
    user_id     VARCHAR(50)   NOT NULL,
    amount      DECIMAL(10,2) NOT NULL,
    nonce       VARCHAR(100)  NOT NULL,                 -- client idempotency nonce
    status      VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    CHECK (status IN ('PENDING','COMPLETED','FAILED')),
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(user_id)
);

CREATE INDEX IF NOT EXISTS idx_bkash_user   ON bkash_pending_payments (user_id);
CREATE INDEX IF NOT EXISTS idx_bkash_status ON bkash_pending_payments (status);

-- ── User Notifications (persisted for notification center with timestamps) ───
CREATE TABLE IF NOT EXISTS notifications (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id        VARCHAR(50)   NOT NULL,
    type           VARCHAR(50)   NOT NULL,
    title          VARCHAR(150)  NOT NULL,
    message        VARCHAR(500)  NOT NULL,
    amount         DECIMAL(10,2) DEFAULT 0.00,
    sender_name    VARCHAR(100),
    transaction_id VARCHAR(100),
    is_read        BOOLEAN       DEFAULT FALSE,
    created_at     TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(user_id)
);

CREATE INDEX IF NOT EXISTS idx_notif_user ON notifications (user_id);
CREATE INDEX IF NOT EXISTS idx_notif_time ON notifications (created_at);
