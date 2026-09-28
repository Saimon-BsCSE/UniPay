# ৳ UniPay — Enclosed Campus Money Management & Real-Time Digital Wallet

**Advanced Object-Oriented Programming (CSE 3118 / CSE 2118) — Lab Project**
Department of Computer Science and Engineering, United International University (UIU)

| | |
|---|---|
| **Section** | J |
| **Group** | 2 |
| **Team** | Md Saimon Islam (0112330140) · Osama Bin Mansur (0112330378) · Md Sadman Sakib (0112330586) |
| **Submitted to** | Sheikh Mohammed Al Hadi Elaf, Lecturer, Dept. of CSE |
| **Stack** | Java 21 · Spring Boot 3.5.3 · MySQL 8 (H2 demo profile) · Spring Security + JWT · Spring Data JPA · WebSockets (STOMP/SockJS) · Tailwind CSS · Vanilla JS · Swagger/OpenAPI |

---

## 1. What UniPay is

UniPay is an **enclosed digital financial platform** for the UIU campus ecosystem. Students,
faculty and staff log in with verified University IDs, top up through simulated MFS sandboxes
(bKash / Nagad / Rocket) at **zero service charge**, pay campus vendors by scanning their **QR
codes with zero transaction fees**, and send each other money instantly. **Cash-out does not
exist** — funds keep circulating inside the verified campus economy.

### Core features (per approved proposal)

| # | Feature | Where |
|---|---------|-------|
| 1 | ID-verified role-based authentication (STUDENT / FACULTY / STAFF / VENDOR) | `auth`, `security` packages |
| 2 | MFS online cash-in gateway (bKash · Nagad · Rocket sandboxes, zero charge) | `mfs` package |
| 3 | QR-based vendor POS — static & dynamic (one-time) QR, zero charge | `vendor`, `payment` packages |
| 4 | Zero-fee P2P transfer by University ID **or** phone number | `payment` package |
| 5 | Enclosed ecosystem — no cash-out endpoint exists by design | (absence is the feature) |

## 2. Quick start

### 2.1 Instant demo (H2 in-memory, zero setup)

```bash
./mvnw spring-boot:run                     # or: java -jar target/unipay-1.0.0.jar
```

Open **http://localhost:8080** — a demo campus is seeded automatically on first boot
(password for every seeded account: **`demo1234`**, MFS sandbox OTP: **`123456`**).

| Role | User ID | Name | Seeded balance |
|------|---------|------|---------------:|
| STUDENT | `0112330140` | Md Saimon Islam | ৳1,500 |
| STUDENT | `0112330378` | Osama Bin Mansur | ৳1,200 |
| STUDENT | `0112330586` | Md Sadman Sakib | ৳800 |
| FACULTY | `0111910667` | Dr. Nafees Ahmed | ৳2,000 |
| STAFF | `UIU-STF-101` | Rezaul Karim | ৳500 |
| VENDOR | `V-CAFE-01` | UIU Central Canteen | — |
| VENDOR | `V-BOOK-01` | UIU Book Shop | — |
| VENDOR | `V-FOOD-01` | Campus Samosa Corner | — |

### 2.2 Submission profile (MySQL 8 — as required by the proposal)

```bash
docker compose up -d                       # MySQL 8 on :3306 (db: unipay, user/pass: unipay/unipay)
./mvnw spring-boot:run -Dspring-boot.run.profiles=mysql
```

The exact proposal DDL (with `ENUM` columns) lives in
[`db/schema-mysql.sql`](db/schema-mysql.sql) and is applied automatically
(`CREATE TABLE IF NOT EXISTS` — safe on every boot). Connection settings
override via `MYSQL_HOST`, `MYSQL_PORT`, `MYSQL_DB`, `MYSQL_USER`, `MYSQL_PASSWORD`.

### 2.3 Build & test

```bash
./mvnw clean verify        # 46 tests: unit (Mockito) + integration + concurrency proof
./mvnw clean package       # → target/unipay-1.0.0.jar
```

| URL | Purpose |
|-----|---------|
| http://localhost:8080 | SPA (login, wallet, QR POS, dashboards) |
| http://localhost:8080/swagger-ui.html | Interactive OpenAPI documentation |
| http://localhost:8080/h2-console | H2 console (H2 profile only, JDBC URL `jdbc:h2:mem:unipay`) |

## 3. Mandatory course requirements — how each is met

| Requirement | Implementation in UniPay |
|-------------|--------------------------|
| **Spring Boot framework** | RESTful API + Spring Security RBAC (`STUDENT`/`FACULTY`/`STAFF`/`VENDOR` via `@PreAuthorize`) + Spring Data JPA repositories. |
| **Multithreading — pessimistic locking** | `WalletRepository.findWalletForUpdateByUserId` uses `@Lock(PESSIMISTIC_WRITE)` (`SELECT … FOR UPDATE`) with a 4 s `lock.timeout` hint. Every money-mover locks **both** wallets in **ascending user-ID order** — the deterministic global order that kills the ABBA deadlock (proposal risk #1). |
| **Multithreading — async workers & thread pools** | `AsyncConfig` defines a **bounded** `ThreadPoolTaskExecutor` (core 4 / max 8 / queue 100) with `AbortPolicy` so saturation can never starve checkout threads (proposal risk #3). `@Async` workers: WebSocket notification dispatch (`NotificationService`) and audit-trail logging (`AuditService`). The financial ledger row itself commits synchronously in the same ACID transaction as the balances — a deliberate correctness decision documented in `ARCHITECTURE.md §4`. |
| **Networking — WebSockets (STOMP/SockJS)** | `/ws` SockJS endpoint; server pushes `/topic/notifications/{userId}` (live P2P received alerts) and `/topic/vendor/{vendorId}` (POS payment banner + audio chime). Clients authenticate at STOMP CONNECT with the same JWT; a subscription guard stops users subscribing to other people's topics. The SPA auto-reconnects with exponential backoff + polling fallback (proposal risk #2). |
| **Database (MySQL + JPA)** | Exact proposal schema (`Users`, `Wallets`, `Vendor_Profiles`, `Transactions`) — `db/schema-mysql.sql`. |
| **Security (JWT + RBAC)** | Stateless HS256 JWTs (jjwt), BCrypt password hashes, method-level role checks. |
| **Frontend (Tailwind + Vanilla JS SPA)** | Served from `src/main/resources/static`; camera QR scanning via `html5-qrcode` with a **manual vendor-ID fallback** when no camera is available. |
| **API docs (Swagger/OpenAPI)** | springdoc-openapi at `/swagger-ui.html` with bearer-auth scheme. |
| **Testing (JUnit 5 + Mockito)** | 46 tests incl. `ConcurrentP2PTest` — 50 simultaneous bidirectional transfers between the same two wallets with exact-to-the-cent balance conservation (the small-scale twin of the proposal's JMeter strategy). |

## 4. Proposal risk matrix → mitigation shipped

| Proposal risk | Shipped mitigation |
|---------------|--------------------|
| DB deadlocks (simultaneous cross P2P) | Ascending user-ID lock ordering (`PaymentService.lockBothWallets`) + 4 s pessimistic lock timeout. Proven by `ConcurrentP2PTest`. |
| WebSocket drops on vendor devices | SockJS fallback transports + client reconnect w/ exponential backoff (1→30 s) + 15 s polling fallback (`static/js/ws.js`). |
| Server thread exhaustion | Bounded `ThreadPoolTaskExecutor` + `AbortPolicy` + logged `AsyncUncaughtExceptionHandler` (`AsyncConfig`). |
| Duplicate payment submission | Client-generated UUID nonce → ledger PK `TXN-<nonce>`; duplicates replay the original response with `duplicate: true` (idempotent by primary key). |

## 5. Project layout

```
unipay/
├── docker-compose.yml              # MySQL 8 for the submission profile
├── db/schema-mysql.sql             # EXACT proposal DDL (ENUMs) — auto-applied by 'mysql' profile
├── db/schema-h2.sql                # mirror for the zero-setup demo profile
├── docs/ARCHITECTURE.md            # diagrams: components, ER, sequences, threading model
├── docs/API.md                     # full endpoint reference
└── src
    ├── main/java/bd/edu/uiu/unipay
    │   ├── auth/                   # registration & login (JWT)
    │   ├── mfs/                    # Strategy: MfsGateway per provider (Template Method base)
    │   ├── payment/                # P2P + vendor QR engine (locks, idempotency)
    │   ├── vendor/                 # stall profiles, static/dynamic QR, ZXing renderer
    │   ├── wallet/                 # balances, ledger history
    │   ├── transaction/            # central ledger entity/repository
    │   ├── notification/           # @Async STOMP push workers
    │   ├── audit/                  # @Async audit trail
    │   ├── security/  config/  ws/ # JWT filter, RBAC, thread pool, WebSocket security
    │   └── common/  bootstrap/     # errors, TxCallbacks, demo data seeder
    ├── main/resources
    │   ├── application*.yml        # shared + h2 + mysql profiles
    │   └── static/                 # Tailwind SPA (index.html, js/, css/)
    └── test/java/...               # unit + integration + concurrency tests
```

## 6. Notes & assumptions

- **MFS sandboxes are simulated** (no real money). Any `01[3-9]XXXXXXXX` number is accepted;
  the demo OTP is **123456**; provider-specific latency is simulated.
- The **frontend loads Tailwind, SockJS, stomp.js and html5-qrcode from CDNs**, so the browser
  needs internet access (per the proposal's chosen stack). The QR flow also works without a
  camera via manual vendor-ID entry.
- Amount policy: transfers/payments ৳1–50,000 (≤2 decimals); cash-ins ৳20–50,000.
- `Transactions.timestamp` is the exact proposal column name; the H2 demo URL adds
  `NON_KEYWORDS=TIMESTAMP` because H2 treats it as a keyword (MySQL is unaffected).

## 7. Security note for anyone deploying this

UniPay is a **university lab project**, not a production fintech deployment, and it ships with
convenience defaults so that `./mvnw spring-boot:run` works with zero configuration. If you ever
host it anywhere other than your own machine, override these **before** exposing it:

| Variable | Why it matters | Default in `application.yml` |
|----------|----------------|------------------------------|
| `UNIPAY_JWT_SECRET` | HS256 signing key. The committed default is **public knowledge**, so anyone can mint valid tokens for any role. | a fixed placeholder string |
| `MYSQL_PASSWORD` | Database password. | `unipay` |
| `BKASH_APP_KEY` / `BKASH_APP_SECRET` / `BKASH_USERNAME` / `BKASH_PASSWORD` | Real bKash tokenized-checkout credentials. The defaults are literal `SANDBOX_*` strings and are **not** live credentials. | `SANDBOX_*` placeholders |

Also disable `app.*` demo affordances and the H2 console (`spring.h2.console.enabled: false`) before
any public deployment. Every other credential in the tree is either a seeded demo account
(password `demo1234`, documented above on purpose) or a test fixture.

