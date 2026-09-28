# UniPay API Reference

Base URL: `http://localhost:8080` · Interactive docs: **`/swagger-ui.html`**
All authenticated endpoints expect `Authorization: Bearer <jwt>` (from login/register).
Errors use a uniform envelope: `{"status": 409, "message": "…", "timestamp": "…"}`.

## Auth — `/api/auth` (public)

| Method | Path | Body | Response |
|--------|------|------|----------|
| POST | `/register` | `{"userId","fullName","phoneNumber","password","role", ("stallName","stallCategory" for VENDOR)}` | **201** `{"token","user"}` · 409 duplicate ID/phone · 400 validation |
| POST | `/login` | `{"userId","password"}` | **200** `{"token","user"}` · 401 bad credentials |

```jsonc
// POST /api/auth/login
{ "userId": "0112330140", "password": "demo1234" }
// →
{ "token": "eyJhbGciOiJIUzUxMiJ9…",
  "user": { "userId": "0112330140", "fullName": "Md Saimon Islam",
            "phoneNumber": "01712345640", "role": "STUDENT" } }
```

## Wallet — `/api/wallet` (any role)

| Method | Path | Response |
|--------|------|----------|
| GET | `/` | `{"userId","fullName","role","balance","updatedAt"}` |
| GET | `/transactions?page=0&size=20` | page of `{"transactionId","type","amount","direction":"IN\|OUT","counterpartyId","counterpartyName","timestamp"}` |

## MFS Cash-In — `/api/mfs` (any role)

| Method | Path | Body / Response |
|--------|------|-----------------|
| GET | `/providers` | list of `{"code","name","icon","minAmount","maxAmount"}` |
| POST | `/cash-in` | `{"provider":"BKASH\|NAGAD\|ROCKET","amount","mfsPhoneNumber","otp","nonce"}` → `PaymentResponse` · 402 wrong OTP · 400 invalid phone/limits |

Sandbox rules: BD mobile `01[3-9]XXXXXXXX`, demo OTP **123456**, ৳20–50,000.

## Payments — `/api/payments` (any role)

### POST `/p2p` — zero-fee transfer
```jsonc
{ "recipient": "0112330378",        // University ID OR phone number
  "amount": 150.00,
  "nonce": "8f0c…-uuid" }            // client-generated UUID (idempotency key)
// → 200
{ "transactionId": "TXN-8f0c…", "type": "P2P_TRANSFER", "amount": 150.0,
  "senderId": "0112330140", "receiverId": "0112330378",
  "receiverName": "Osama Bin Mansur", "payerNewBalance": 1850.0,
  "timestamp": "…", "duplicate": false }
```
404 unknown recipient · 400 self/invalid amount · **409 insufficient balance** ·
re-sending the same `nonce` returns the original result with `duplicate: true`.

### POST `/vendor` — zero-charge QR payment
```jsonc
// either the scanned payload…
{ "payload": "UNIPAY:VENDOR:V-CAFE-01:AMT:49.50:NONCE:uuid:TS:…", "amount": 49.50, "nonce": "…" }
// …or manual entry when no camera is available
{ "vendorId": "V-CAFE-01", "amount": 30.00, "nonce": "…" }
```
**409** dynamic QR expired/already used · **400** amount ≠ dynamic preset ·
triggers the vendor's live POS banner + audio chime over WebSocket.

## Vendor POS — `/api/vendor` (VENDOR role only — 403 otherwise)

| Method | Path | Response |
|--------|------|----------|
| GET | `/profile` | `{"vendorId","stallName","stallCategory","qrCodeIdentifier"}` · 404 `VENDOR_PROFILE_INCOMPLETE` until configured |
| PUT | `/profile` | `{"stallName","stallCategory"}` → upsert |
| GET | `/qr?type=STATIC\|DYNAMIC&amount=` | `{"type","vendorId","payload","presetAmount","expiresAt"}` |
| GET | `/qr.png?type=…&amount=…` | `image/png` (320×320 ZXing) |
| GET | `/stats` | `{"todaySales","todayCount"}` — since midnight, Asia/Dhaka |

**QR payload grammar**
`UNIPAY:VENDOR:<vendorId> [:AMT:<amount>] [:NONCE:<one-time-uuid>] [:TS:<epochMs>]`
Static QR = `UNIPAY:VENDOR:<id>` (reusable). Dynamic QR nonce is single-use, TTL 5 min.

## WebSocket — `/ws` (SockJS + STOMP)

1. `new SockJS('/ws?token=' + jwt)` then `stompClient.connect({Authorization: 'Bearer ' + jwt}, …)`
2. Subscribe (only your own topics — enforced server-side):
   - `/topic/notifications/{yourUserId}` — P2P received & cash-in events
   - `/topic/vendor/{yourVendorId}` — POS payment alerts (VENDOR)
3. Server event frame:
```jsonc
{ "type": "VENDOR_PAYMENT", "title": "Payment received",
  "message": "Md Saimon Islam paid ৳49.50", "amount": 49.5,
  "senderName": "Md Saimon Islam", "transactionId": "TXN-…", "timestamp": "…" }
```

## HTTP status conventions

| Code | Meaning |
|------|---------|
| 400 | validation / malformed QR / business rule |
| 401 | missing/invalid JWT or credentials |
| 402 | MFS sandbox rejected (wrong OTP) |
| 403 | role not permitted (RBAC) |
| 404 | unknown user/vendor |
| 409 | insufficient balance · duplicate nonce · QR already used |
