# ---------------------------------------------------------------------------
# UniPay — exact schema from the approved project requirement (MySQL 8.x)
# Extensions beyond the proposal (marked "PERF:") are non-destructive indexes
# added for query performance. No proposal column has been altered or removed.
# ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS Users (
    user_id       VARCHAR(50)  PRIMARY KEY,                                  -- Student / Faculty / Staff / Vendor ID
    full_name     VARCHAR(100) NOT NULL,
    phone_number  VARCHAR(15)  UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          ENUM('STUDENT','FACULTY','STAFF','VENDOR') NOT NULL,
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS Wallets (
    wallet_id       INT AUTO_INCREMENT PRIMARY KEY,
    user_id         VARCHAR(50) UNIQUE NOT NULL,
    current_balance DECIMAL(10, 2) DEFAULT 0.00,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES Users(user_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS Vendor_Profiles (
    vendor_id           VARCHAR(50) PRIMARY KEY,
    stall_name          VARCHAR(100) NOT NULL,
    stall_category      ENUM('CANTEEN','BOOKSHOP','FOOD_STALL','OTHER') NOT NULL,
    qr_code_identifier  VARCHAR(255) UNIQUE NOT NULL,
    FOREIGN KEY (vendor_id) REFERENCES Users(user_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS Transactions (
    transaction_id    VARCHAR(100) PRIMARY KEY,                              -- 'TXN-' + client idempotency nonce
    sender_id         VARCHAR(50) NOT NULL,
    receiver_id       VARCHAR(50) NOT NULL,
    amount            DECIMAL(10, 2) NOT NULL,
    transaction_type  ENUM('MFS_CASH_IN','VENDOR_PAYMENT','P2P_TRANSFER','SPLIT_PAY','VENDOR_CASHOUT','LOYALTY_REDEMPTION') NOT NULL,
    timestamp         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (sender_id) REFERENCES Users(user_id),
    FOREIGN KEY (receiver_id) REFERENCES Users(user_id),
    INDEX idx_txn_sender   (sender_id),                                       -- PERF: ledger lookups
    INDEX idx_txn_receiver (receiver_id),                                     -- PERF: ledger lookups
    INDEX idx_txn_time     (timestamp)                                        -- PERF: end-of-day reports
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS Split_Bills (
    bill_id          VARCHAR(50) PRIMARY KEY,
    creator_id       VARCHAR(50) NOT NULL,
    title            VARCHAR(100) NOT NULL,
    total_amount     DECIMAL(10, 2) NOT NULL,
    split_type       ENUM('EVEN','CUSTOM') NOT NULL,
    status           ENUM('ACTIVE','SETTLED','CANCELLED') NOT NULL,
    note             VARCHAR(255),
    created_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (creator_id) REFERENCES Users(user_id),
    INDEX idx_split_creator (creator_id),
    INDEX idx_split_status  (status)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS Split_Requests (
    request_id       VARCHAR(50) PRIMARY KEY,
    bill_id          VARCHAR(50) NOT NULL,
    participant_id   VARCHAR(50) NOT NULL,
    amount           DECIMAL(10, 2) NOT NULL,
    status           ENUM('PENDING','ACCEPTED','DECLINED','CANCELLED') NOT NULL,
    transaction_id   VARCHAR(100),
    paid_at          TIMESTAMP NULL,
    created_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (bill_id) REFERENCES Split_Bills(bill_id) ON DELETE CASCADE,
    FOREIGN KEY (participant_id) REFERENCES Users(user_id),
    INDEX idx_req_participant (participant_id),
    INDEX idx_req_bill        (bill_id),
    INDEX idx_req_status      (status)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS Vendor_Cashouts (
    cashout_id          VARCHAR(50) PRIMARY KEY,
    vendor_id           VARCHAR(50) NOT NULL,
    amount              DECIMAL(10, 2) NOT NULL,
    channel             ENUM('BKASH','NAGAD','ROCKET','BANK') NOT NULL,
    account_number      VARCHAR(50) NOT NULL,
    account_holder_name VARCHAR(100),
    bank_name           VARCHAR(100),
    branch_name         VARCHAR(100),
    transaction_id      VARCHAR(100) NOT NULL,
    status              ENUM('COMPLETED','PROCESSING') NOT NULL DEFAULT 'COMPLETED',
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (vendor_id) REFERENCES Users(user_id),
    INDEX idx_cashout_vendor (vendor_id),
    INDEX idx_cashout_time   (created_at)
) ENGINE=InnoDB;

-- ── OTP Records (hashed, TTL enforced via expires_at) ────────────────────────
CREATE TABLE IF NOT EXISTS otp_records (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id      VARCHAR(50)   NOT NULL,
    provider     VARCHAR(10)   NOT NULL,
    phone_number VARCHAR(15)   NOT NULL,
    otp_hash     VARCHAR(255)  NOT NULL,
    amount       DECIMAL(10,2) NOT NULL,
    nonce        VARCHAR(100)  NOT NULL,
    attempts     INT           NOT NULL DEFAULT 0,
    max_attempts INT           NOT NULL DEFAULT 3,
    expires_at   TIMESTAMP     NOT NULL,
    created_at   TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES Users(user_id),
    INDEX idx_otp_user    (user_id),
    INDEX idx_otp_expires (expires_at)
) ENGINE=InnoDB;

-- ── Loyalty Points (one row per user, running total) ─────────────────────────
CREATE TABLE IF NOT EXISTS loyalty_points (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id      VARCHAR(50)   NOT NULL UNIQUE,
    total_points DECIMAL(12,4) NOT NULL DEFAULT 0.0000,
    FOREIGN KEY (user_id) REFERENCES Users(user_id),
    INDEX idx_loyalty_user (user_id)
) ENGINE=InnoDB;
