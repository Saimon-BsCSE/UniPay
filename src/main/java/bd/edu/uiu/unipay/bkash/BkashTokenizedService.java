package bd.edu.uiu.unipay.bkash;

import bd.edu.uiu.unipay.audit.AuditService;
import bd.edu.uiu.unipay.bkash.BkashDTOs.CheckoutInitResponse;
import bd.edu.uiu.unipay.bkash.BkashDTOs.CreatePaymentRequest;
import bd.edu.uiu.unipay.bkash.BkashDTOs.CreatePaymentResponse;
import bd.edu.uiu.unipay.bkash.BkashDTOs.ExecutePaymentRequest;
import bd.edu.uiu.unipay.bkash.BkashDTOs.ExecutePaymentResponse;
import bd.edu.uiu.unipay.bkash.BkashDTOs.GrantTokenRequest;
import bd.edu.uiu.unipay.bkash.BkashDTOs.GrantTokenResponse;
import bd.edu.uiu.unipay.bkash.BkashDTOs.QueryPaymentResponse;
import bd.edu.uiu.unipay.bkash.BkashPendingPayment.Status;
import bd.edu.uiu.unipay.common.ApiException;
import bd.edu.uiu.unipay.common.TxCallbacks;
import bd.edu.uiu.unipay.notification.NotificationService;
import bd.edu.uiu.unipay.transaction.Transaction;
import bd.edu.uiu.unipay.transaction.TransactionRepository;
import bd.edu.uiu.unipay.transaction.TransactionType;
import bd.edu.uiu.unipay.user.Role;
import bd.edu.uiu.unipay.user.User;
import bd.edu.uiu.unipay.user.UserRepository;
import bd.edu.uiu.unipay.wallet.Wallet;
import bd.edu.uiu.unipay.wallet.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/**
 * Real bKash Tokenized Checkout integration (v1.2.0-beta).
 *
 * <h3>Flow</h3>
 * <ol>
 *   <li>{@link #initiateCheckout} — Grant Token (cached) → Create Payment →
 *       persist {@link BkashPendingPayment}(PENDING) → return {paymentID, bkashURL}
 *       to the frontend for redirection.</li>
 *   <li>User enters PIN / OTP on the bKash gateway page.</li>
 *   <li>{@link #handleCallback} — bKash calls our callback URL with
 *       {@code paymentID} and {@code status}. On {@code "success"}: Execute
 *       Payment → credit wallet → save Transaction → mark COMPLETED → fire
 *       async notifications + audit. On failure/cancel: mark FAILED.</li>
 * </ol>
 *
 * <h3>Concurrency contract</h3>
 * <ul>
 *   <li>Token caching uses {@code synchronized} + {@link Instant} expiry
 *       with a 60-second safety buffer.</li>
 *   <li>The wallet credit and Transaction persist happen inside a single
 *       {@code @Transactional} boundary — ACID-safe against the same
 *       double-execution guard used by the rest of the ledger.</li>
 *   <li>The ledger key is {@code "BKASH-" + paymentID}, so a duplicate
 *       callback (bKash may retry) is silently idempotent.</li>
 * </ul>
 */
@Service
public class BkashTokenizedService {

    /** ID of the bootstrapped system user that acts as "sender" on bKash cash-in ledger rows. */
    public static final String BKASH_GATEWAY_USER_ID = "BKASH_GATEWAY";

    private static final String BKASH_MODE = "0011";          // Tokenized Checkout mode
    private static final String BKASH_CURRENCY = "BDT";
    private static final String BKASH_INTENT = "sale";
    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_CODE_OK = "0000";
    private static final String TX_STATUS_COMPLETED = "Completed";
    private static final long TOKEN_EXPIRY_BUFFER_SECONDS = 60L;

    private static final Logger log = LoggerFactory.getLogger(BkashTokenizedService.class);

    private final BkashProperties          properties;
    private final RestClient               restClient;
    private final UserRepository           users;
    private final WalletRepository         wallets;
    private final TransactionRepository    transactions;
    private final BkashPendingPaymentRepository pendingPayments;
    private final NotificationService      notifications;
    private final AuditService             audit;


    // ── Cached token state ────────────────────────────────────────────────────
    private String  cachedIdToken;
    private Instant tokenExpiryTime;

    public BkashTokenizedService(BkashProperties properties,
                                 RestClient.Builder restClientBuilder,
                                 UserRepository users,
                                 WalletRepository wallets,
                                 TransactionRepository transactions,
                                 BkashPendingPaymentRepository pendingPayments,
                                 NotificationService notifications,
                                 AuditService audit) {
        this.properties     = properties;
        this.restClient     = restClientBuilder.baseUrl(properties.getBaseUrl()).build();
        this.users          = users;
        this.wallets        = wallets;
        this.transactions   = transactions;
        this.pendingPayments = pendingPayments;
        this.notifications  = notifications;
        this.audit          = audit;
    }

    // ── 1. Grant Token (cached) ───────────────────────────────────────────────

    /**
     * Returns a valid bKash {@code id_token}, reusing the cached value if it
     * has not yet expired (minus a 60-second safety buffer). Thread-safe.
     */
    public synchronized String getGrantToken() {
        if (cachedIdToken != null && tokenExpiryTime != null
                && Instant.now().isBefore(tokenExpiryTime)) {
            return cachedIdToken;
        }
        log.debug("Requesting new bKash grant token...");
        GrantTokenResponse response = restClient.post()
                .uri("/tokenized/checkout/token/grant")
                .header("username", properties.getUsername())
                .header("password", properties.getPassword())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new GrantTokenRequest(properties.getAppKey(), properties.getAppSecret()))
                .retrieve()
                .body(GrantTokenResponse.class);

        if (response == null || !STATUS_CODE_OK.equals(response.statusCode())) {
            String msg = response != null ? response.statusMessage() : "Empty response";
            throw new ApiException(502, "bKash grant token failed: " + msg);
        }
        cachedIdToken    = response.idToken();
        tokenExpiryTime  = Instant.now().plusSeconds(response.expiresIn() - TOKEN_EXPIRY_BUFFER_SECONDS);
        log.info("bKash grant token refreshed, expires in {}s", response.expiresIn());
        return cachedIdToken;
    }

    // ── 2. Initiate Checkout ──────────────────────────────────────────────────

    /**
     * Step 1 of the bKash flow: creates a payment session and persists a
     * {@link BkashPendingPayment} row so the callback can credit the right wallet.
     *
     * @param userId        authenticated user ID (from JWT)
     * @param amount        amount in BDT
     * @param nonce         client-generated idempotency nonce
     * @return {@link CheckoutInitResponse} containing the bKash redirect URL
     */
    @Transactional
    public CheckoutInitResponse initiateCheckout(String userId, BigDecimal amount, String nonce) {
        User user = users.findById(userId)
                .orElseThrow(() -> ApiException.unauthorized("Authenticated user no longer exists."));

        // Validate amount range (same limits as the simulated gateway)
        if (amount.compareTo(BigDecimal.valueOf(20)) < 0
                || amount.compareTo(BigDecimal.valueOf(50_000)) > 0) {
            throw ApiException.badRequest("bKash cash-in amount must be between ৳20 and ৳50,000.");
        }

        if (!properties.isConfigured()) {
            throw new ApiException(503,
                    "bKash live credentials not configured. Please use MFS Sandbox OTP flow (demo OTP: 123456) or set credentials in application.yml.");
        }

        String formattedAmount = amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
        String invoiceNumber   = "UNIPAY-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();

        CreatePaymentResponse createResp = callCreatePayment(user, formattedAmount, invoiceNumber);

        // Persist the pending session — will be resolved in handleCallback
        BkashPendingPayment pending = new BkashPendingPayment(
                createResp.paymentID(), user, amount, nonce);
        pendingPayments.save(pending);

        log.info("bKash checkout initiated: paymentID={} userId={} amount={}", createResp.paymentID(), userId, formattedAmount);
        return new CheckoutInitResponse(createResp.paymentID(), createResp.bkashURL(), formattedAmount);
    }

    private CreatePaymentResponse callCreatePayment(User user, String formattedAmount, String invoiceNumber) {
        try {
            CreatePaymentResponse resp = restClient.post()
                    .uri("/tokenized/checkout/create")
                    .header("Authorization", getGrantToken())
                    .header("X-APP-Key", properties.getAppKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new CreatePaymentRequest(
                            BKASH_MODE,
                            user.getPhoneNumber(),
                            properties.getCallbackUrl(),
                            formattedAmount,
                            BKASH_CURRENCY,
                            BKASH_INTENT,
                            invoiceNumber))
                    .retrieve()
                    .body(CreatePaymentResponse.class);

            if (resp == null || !STATUS_CODE_OK.equals(resp.statusCode())) {
                String msg = resp != null ? resp.statusMessage() : "Empty response";
                throw new ApiException(502, "bKash Create Payment failed: " + msg);
            }
            return resp;
        } catch (RestClientException ex) {
            log.error("bKash createPayment HTTP error for user {}: {}", user.getUserId(), ex.getMessage());
            throw new ApiException(502, "bKash gateway unreachable. Please try again.");
        }
    }

    // ── 3. Handle Callback ────────────────────────────────────────────────────

    /**
     * Step 3 of the bKash flow: called by the bKash gateway after the user
     * completes or cancels payment on their page.
     *
     * <p>On {@code status=success}: executes the payment, credits the wallet,
     * persists the ledger entry, and marks the session COMPLETED.
     * On any other status (cancel, failure): marks the session FAILED.</p>
     *
     * @param paymentID bKash payment ID from the callback query param
     * @param status    "success" | "cancel" | "failure" from bKash
     * @return redirect URL for the frontend (relative path)
     */
    @Transactional
    public String handleCallback(String paymentID, String status) {
        BkashPendingPayment pending = pendingPayments.findById(paymentID)
                .orElseThrow(() -> ApiException.notFound("Unknown bKash paymentID: " + paymentID));

        if (pending.getStatus() != Status.PENDING) {
            // Idempotent: already processed (duplicate callback from bKash)
            log.warn("Duplicate bKash callback received for paymentID={} status={}", paymentID, pending.getStatus());
            return pending.getStatus() == Status.COMPLETED
                    ? "/payment-success?trxID=duplicate"
                    : "/payment-failed?reason=already_processed";
        }

        if (!STATUS_SUCCESS.equalsIgnoreCase(status)) {
            pending.markFailed();
            log.info("bKash payment cancelled/failed: paymentID={} status={}", paymentID, status);
            return "/payment-failed?reason=" + status;
        }

        // ── Execute Payment ─────────────────────────────────────────────────
        ExecutePaymentResponse execResp = callExecutePayment(paymentID);

        if (!TX_STATUS_COMPLETED.equalsIgnoreCase(execResp.transactionStatus())) {
            pending.markFailed();
            log.warn("bKash execute returned non-Completed status: {} for paymentID={}", execResp.transactionStatus(), paymentID);
            return "/payment-failed?reason=" + execResp.transactionStatus();
        }

        // ── Credit Wallet ───────────────────────────────────────────────────
        User user = pending.getUser();
        Wallet wallet = wallets.findWalletForUpdateByUserId(user.getUserId())
                .orElseThrow(() -> ApiException.notFound("Wallet not found for user: " + user.getUserId()));
        wallet.credit(pending.getAmount());
        wallets.save(wallet);

        // NOTE: Loyalty points are NOT awarded on cash-in (real bKash top-up).
        // Points are earned only on purchases: VENDOR_PAYMENT, P2P_TRANSFER, SPLIT_PAY.

        // ── Persist Ledger Entry ────────────────────────────────────────────
        String txnId = "BKASH-" + paymentID;          // idempotency key
        User gatewayUser = users.findById(BKASH_GATEWAY_USER_ID)
                .orElseThrow(() -> new IllegalStateException(
                        "bKash system user (BKASH_GATEWAY) not found in DB. Check DataSeeder."));

        Transaction txn;
        if (transactions.existsById(txnId)) {
            // Truly duplicate callback — idempotent, wallet already credited above
            // (This branch is protected by the PENDING check above but kept for safety)
            txn = transactions.findById(txnId).orElseThrow();
        } else {
            txn = transactions.save(new Transaction(
                    txnId, gatewayUser, user, pending.getAmount(), TransactionType.MFS_CASH_IN));
        }

        // ── Mark session complete ───────────────────────────────────────────
        pending.markCompleted();

        // ── Async post-commit side-effects ──────────────────────────────────
        final Transaction committed = txn;
        TxCallbacks.afterCommit(() -> {
            notifications.pushBkashCashInCompleted(user, committed, execResp.trxID());
            audit.logLedgerEntry(committed, user.getUserId());
        });

        log.info("bKash payment completed: paymentID={} trxID={} userId={} amount={}",
                paymentID, execResp.trxID(), user.getUserId(), pending.getAmount());
        return "/payment-success?trxID=" + execResp.trxID();
    }

    private ExecutePaymentResponse callExecutePayment(String paymentID) {
        try {
            ExecutePaymentResponse resp = restClient.post()
                    .uri("/tokenized/checkout/execute")
                    .header("Authorization", getGrantToken())
                    .header("X-APP-Key", properties.getAppKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ExecutePaymentRequest(paymentID))
                    .retrieve()
                    .body(ExecutePaymentResponse.class);

            if (resp == null || !STATUS_CODE_OK.equals(resp.statusCode())) {
                String msg = resp != null ? resp.statusMessage() : "Empty response";
                throw new ApiException(502, "bKash Execute Payment failed: " + msg);
            }
            return resp;
        } catch (RestClientException ex) {
            log.error("bKash executePayment HTTP error for paymentID {}: {}", paymentID, ex.getMessage());
            throw new ApiException(502, "bKash gateway unreachable during execute. Contact support.");
        }
    }

    // ── 4. Query Payment Status (reconciliation) ──────────────────────────────

    /**
     * Optional: queries the bKash API for the current status of a payment.
     * Use for reconciliation or customer support lookups.
     */
    public QueryPaymentResponse queryPayment(String paymentID) {
        try {
            return restClient.post()
                    .uri("/tokenized/checkout/payment/status")
                    .header("Authorization", getGrantToken())
                    .header("X-APP-Key", properties.getAppKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ExecutePaymentRequest(paymentID))
                    .retrieve()
                    .body(QueryPaymentResponse.class);
        } catch (RestClientException ex) {
            log.error("bKash queryPayment HTTP error for paymentID {}: {}", paymentID, ex.getMessage());
            throw new ApiException(502, "bKash gateway unreachable during query.");
        }
    }
}
