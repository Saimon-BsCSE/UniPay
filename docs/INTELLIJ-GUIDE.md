# Running the UniPay demo in IntelliJ IDEA — step by step

Works on **IntelliJ IDEA Community or Ultimate** (2023.2 or newer recommended).
The default run uses the **H2 in-memory profile** — nothing to install except
JDK 21 and IntelliJ. MySQL is optional (Step 8).

---

## Step 0 — Get the project onto your machine

1. Download `unipay-project.zip` from the workspace (or copy the `unipay/`
   folder) to your computer, e.g. `C:\Users\<you>\IdeaProjects\unipay-project.zip`.
2. Unzip it. You should see a folder containing `pom.xml`, `src/`, `db/`, `docs/`.

> The zip does **not** contain `target/` — IntelliJ will build it fresh (Step 3).

## Step 1 — Open the project

1. Start IntelliJ → **File → Open…** (or **Open** on the Welcome screen).
2. Select the **unipay** folder — the one that directly contains `pom.xml` — and press **OK**.
3. If asked *"Trust this project?"* → **Trust**.
4. IntelliJ detects Maven and starts importing dependencies (see the progress bar
   at the bottom). **First import downloads a lot — wait 2–5 minutes** until it settles.

## Step 2 — Make sure the JDK is 21

1. **File → Project Structure** (`Ctrl+Alt+Shift+S`) → **Project**.
2. **SDK**: if 21 is listed, pick it. If not: **Add SDK → Download JDK…** →
   Version **21**, Vendor *Eclipse Temurin (Adoptium)* or *Amazon Corretto* → **Download**.
3. **Language level**: leave on *SDK default*. → **OK**.

## Step 3 — Let IntelliJ build once

- Menu **Build → Build Project** (`Ctrl+F9`).
- If the Maven tool window shows a refresh icon with pending changes, click it
  (**Load Maven Changes**, `Ctrl+Shift+O`).

## Step 4 — Run the app (the easy way)

1. In the Project tree open
   `src/main/java/bd/edu/uiu/unipay/UniPayApplication.java`.
2. Click the green **▶** triangle next to `public class UniPayApplication`
   (or the ▶ in the main method) → **Run 'UniPayApplication'**.
3. Watch the Run console. You know it's up when you see:

   ```
   Tomcat started on port 8080 (http) with context path '/'
   Started UniPayApplication in …
   ---------------------------------------------------------------
    UniPay demo campus seeded (password for all: demo1234)
    STUDENT 0112330140 Saimon    | FACULTY 0111910667 Dr. Nafees
    ...
    MFS sandbox OTP: 123456
   ---------------------------------------------------------------
   ```

> Alternative run methods (both fine):
> - **Maven tool window → unipay → Plugins → spring-boot → spring-boot:run**
> - Terminal: `./mvnw spring-boot:run` (Windows: `mvnw.cmd spring-boot:run`)

## Step 5 — Open the demo in a browser

Go to **http://localhost:8080**

| Account | User ID | Password | Seeded balance |
|---|---|---|---:|
| Student | `0112330140` | `demo1234` | ৳1,500 |
| Student | `0112330378` | `demo1234` | ৳1,200 |
| Faculty | `0111910667` | `demo1234` | ৳2,000 |
| Staff | `UIU-STF-101` | `demo1234` | ৳500 |
| **Vendor (canteen)** | `V-CAFE-01` | `demo1234` | — |
| Vendor (book shop) | `V-BOOK-01` | `demo1234` | — |
| Vendor (samosa) | `V-FOOD-01` | `demo1234` | — |

The browser needs **internet** (Tailwind/sockJS/html5-qrcode load from CDNs).

## Step 6 — One-machine demo script (great for the viva)

Use **two browser windows side by side** (or one normal + one incognito):

1. **Left window — log in as the vendor** `V-CAFE-01`.
   You land on the POS dashboard: today's sales, QR code, live banner area.
2. **Right window — log in as a student** `0112330140`.
3. **Cash-in**: student → *Add money* → pick bKash → ৳500 → phone
   `01712345640` → OTP `123456` → balance updates instantly.
4. **P2P**: student → *Send money* → to `0112330378` (or the phone) → ৳150.
5. **Vendor QR pay (no camera needed)**:
   - Vendor window: QR tab → **Dynamic (one-time)** → optionally preset ৳49.50 →
     **Generate QR** → **Copy code**.
   - Student window: *Scan & pay* → paste the copied code into
     *"Vendor ID / pasted code"* → Continue → amount is preset → **Confirm payment**.
   - ⚡ The **vendor window fires the green banner + audio chime instantly**
     (that's the WebSocket/STOMP requirement working live).
6. Try the failure paths (examiners love these): pay the same dynamic QR twice
   (→ "expired or already used"), send more than the balance (→ 409), wrong OTP
   (→ 402), log in as student and open `/api/vendor/stats` (→ 403 RBAC).
7. Real camera test: point the student's *Scan & pay* at the vendor's QR on
   another screen/phone — html5-qrcode decodes it in-browser.

## Step 7 — Explore the backend

- **Swagger UI:** http://localhost:8080/swagger-ui.html
  (Authorize button → paste the JWT from a login call).
- **H2 console:** http://localhost:8080/h2-console — JDBC URL
  `jdbc:h2:mem:unipay`, user `sa`, empty password. Tables:
  `USERS`, `WALLETS`, `VENDOR_PROFILES`, `TRANSACTIONS`.

## Step 8 (optional) — Run against MySQL like the submission

1. Install Docker Desktop, then in IntelliJ's **Terminal** (project folder):
   ```
   docker compose up -d
   ```
2. Activate the `mysql` profile:
   - **Ultimate:** Run → Edit Configurations… → select `UniPayApplication` →
     **Active profiles:** `mysql` → Run.
   - **Community:** Run → Edit Configurations… → **Environment variables:**
     `SPRING_PROFILES_ACTIVE=mysql` → Run.
   The exact proposal DDL (`db/schema-mysql.sql`) is applied automatically.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `invalid target release: 21` / class-version error | Step 2 not done — Project SDK must be JDK 21. |
| Port 8080 already in use | Stop the other process, or set `server.port: 8081` in `application.yml`. |
| Dependencies won't resolve | Check internet/proxy; Maven tool window → refresh (Reload All Maven Projects). |
| Page loads but looks unstyled | The browser (not IntelliJ) needs internet for the Tailwind CDN. |
| Camera does nothing in *Scan & pay* | Expected without a camera / non-HTTPS — use the manual entry field (by design). |
| `mvnw` permission denied (Linux/mac) | `chmod +x mvnw` — or just use IntelliJ's bundled Maven. |
| Tests fail on your machine | Run `mvn clean verify` once first; ensure nothing else holds port 8080. |

## Stopping / restarting

- Stop: red ■ square in the Run window (or Ctrl+F5).
- Restart: green ▶ again. H2 is in-memory, so each restart re-seeds the fresh
  demo campus — balances reset. That's intentional for demos.
