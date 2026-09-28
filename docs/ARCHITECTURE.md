# UniPay — Architecture

Advanced OOP (CSE 3118 / CSE 2118) · UIU · Group 2, Section J

## 1. Component view

```mermaid
flowchart LR
    subgraph Client["Browser (SPA — Tailwind + Vanilla JS)"]
        UI[User dashboard<br/>cash-in · send · scan &amp; pay]
        VD[Vendor dashboard<br/>QR generator · POS banner + chime]
        QR[html5-qrcode camera scanner<br/>+ manual entry fallback]
    end

    subgraph Spring["Spring Boot 3.5 (Java 21)"]
        SEC[Spring Security<br/>JWT filter + RBAC]
        API[REST controllers<br/>/api/auth /api/wallet /api/mfs<br/>/api/payments /api/vendor]
        PS[PaymentService<br/>pessimistic locks + idempotency]
        MFS[MFS gateways<br/>Strategy: bKash/Nagad/Rocket]
        WS[STOMP broker /ws<br/>SockJS fallbacks]
        AS[AsyncConfig — bounded ThreadPoolTaskExecutor]
        NOT[NotificationService @Async]
        AUD[AuditService @Async]
        JPA[Spring Data JPA repositories]
    end

    subgraph Data["Persistence"]
        DB[(MySQL 8<br/>Users · Wallets ·<br/>Vendor_Profiles · Transactions)]
        H2[(H2 demo profile<br/>MySQL compatibility mode)]
    end

    UI -->|HTTPS + JWT| SEC --> API
    VD --> SEC
    QR --> UI
    API --> PS & MFS
    PS --> JPA --> DB
    MFS --> JPA
    PS -->|after commit| AS --> NOT & AUD
    NOT --> WS
    WS -->|/topic/**| UI & VD
    JPA --> H2
```

## 2. ER model (exact proposal schema)

```mermaid
erDiagram
    Users ||--o| Wallets : "1 wallet per user"
    Users ||--o| Vendor_Profiles : "merchant metadata"
    Users ||--o{ Transactions : "sender"
    Users ||--o{ Transactions : "receiver"

    Users {
        varchar user_id PK "Student/Faculty/Staff/Vendor ID"
        varchar full_name
        varchar phone_number UK
        varchar password_hash "BCrypt"
        enum role "STUDENT|FACULTY|STAFF|VENDOR"
        timestamp created_at
    }
    Wallets {
        int wallet_id PK "AUTO_INCREMENT"
        varchar user_id FK, UK
        decimal current_balance "10,2"
        timestamp updated_at
    }
    Vendor_Profiles {
        varchar vendor_id PK,FK
        varchar stall_name
        enum stall_category "CANTEEN|BOOKSHOP|FOOD_STALL|OTHER"
        varchar qr_code_identifier UK "UNIPAY:VENDOR:<id>"
    }
    Transactions {
        varchar transaction_id PK "TXN-<client nonce> (idempotency)"
        varchar sender_id FK
        varchar receiver_id FK
        decimal amount "10,2"
        enum transaction_type "MFS_CASH_IN|VENDOR_PAYMENT|P2P_TRANSFER"
        timestamp timestamp
    }
```

## 3. QR vendor payment — sequence

```mermaid
sequenceDiagram
    participant P as Payer (SPA + camera)
    participant V as Vendor dashboard
    participant API as PaymentService
    participant DB as MySQL (InnoDB)
    participant TP as Bounded thread pool
    participant WS as STOMP broker

    V->>API: GET /api/vendor/qr?type=DYNAMIC&amount=49.50
    API-->>V: UNIPAY:VENDOR:V-CAFE-01:AMT:49.50:NONCE:uuid:TS:…
    Note over V: QR rendered (ZXing PNG)
    P->>P: scan & parse payload
    P->>API: POST /api/payments/vendor {payload, 49.50, nonce}
    API->>DB: SELECT … FOR UPDATE wallet(lower-id)
    API->>DB: SELECT … FOR UPDATE wallet(higher-id)
    API->>API: validate vendor, preset amount, balance
    API->>DB: redeem 1-time QR nonce (in-memory cache)
    API->>DB: debit payer · credit vendor · INSERT ledger row (TXN-<nonce>)
    API-->>P: 200 PaymentResponse (payerNewBalance)
    Note over API,DB: ACID commit
    API-->>TP: afterCommit → dispatch events
    TP->>WS: /topic/vendor/V-CAFE-01 {VENDOR_PAYMENT, 49.50}
    TP->>TP: audit trail log line
    WS-->>V: banner + 🔔 audio chime (≤200 ms target)
```

## 4. Threading & concurrency model

```mermaid
flowchart TB
    subgraph Tomcat["Tomcat request threads (payment path — never blocked)"]
        REQ1[POST /api/payments/p2p A→B]
        REQ2[POST /api/payments/p2p B→A]
    end

    subgraph TX["@Transactional — serialised by row locks"]
        L1["lock wallets in ASCENDING user-ID order<br/>(global order ⇒ no ABBA deadlock)"]
        L2[debit / credit / INSERT ledger TXN-<nonce>]
    end

    subgraph Pool["unipayExecutor — bounded (core 4 · max 8 · queue 100 · AbortPolicy)"]
        N1[NotificationService @Async<br/>STOMP pushes]
        N2[AuditService @Async<br/>audit trail]
    end

    REQ1 --> L1
    REQ2 --> L1
    L1 --> L2
    L2 -->|afterCommit| Pool
```

**Why the ledger insert is synchronous while notifications are async.** The proposal lists
"transaction logging" among `@Async` workers. UniPay writes the *financial ledger row* inside
the same ACID transaction as the balance updates — otherwise a crash between "balance moved"
and "async ledger write" would lose money history. The *audit trail* and *real-time
notifications* (non-critical I/O) run on the bounded pool exactly as required, dispatched only
after commit via `TransactionSynchronization.afterCommit`.

**Idempotency.** Ledger PK = `TXN-<client nonce>`; a repeated tap re-reads the committed row
and returns it with `duplicate:true` — no second debit, no second credit.

## 5. Real-time channel security

- SockJS handshake accepts `?token=<jwt>` (xhr fallbacks can't send STOMP headers).
- `StompAuthChannelInterceptor` verifies the JWT at STOMP CONNECT and installs the principal.
- `SubscriptionGuardInterceptor` rejects subscriptions to `/topic/**/{someoneElse}`.
- Client reconnects with exponential backoff 1→30 s; after 3 failures it also polls every 15 s
  until the socket returns (proposal risk: connection drops).

## 6. OOP design patterns used (course relevance)

| Pattern | Where |
|---------|-------|
| **Strategy** | `MfsGateway` per provider; `MfsGatewayFactory` registry |
| **Template Method** | `AbstractSimulatedMfsGateway.verify()` shared phone/OTP/limit algorithm, provider-specific latency hook |
| **Adapter** | `AppUserPrincipal` adapts the domain `User` to Spring Security `UserDetails`/`Principal` |
| **Repository / DAO** | Spring Data JPA interfaces over the proposal schema |
| **DTO / Mapper** | `*Dtos` records isolating the API contract from entities |
| **Facade** | `PaymentService` orchestrates locking, idempotency, ledger, events |
| **Producer–Consumer** | bounded `ThreadPoolTaskExecutor` + after-commit event dispatch |
| **Singleton / DI** | Spring components wired by constructor injection throughout |
