package bd.edu.uiu.unipay.payment;

import bd.edu.uiu.unipay.audit.AuditService;
import bd.edu.uiu.unipay.common.ApiException;
import bd.edu.uiu.unipay.common.TxCallbacks;
import bd.edu.uiu.unipay.loyalty.LoyaltyService;
import bd.edu.uiu.unipay.notification.NotificationService;
import bd.edu.uiu.unipay.transaction.Transaction;
import bd.edu.uiu.unipay.transaction.TransactionRepository;
import bd.edu.uiu.unipay.transaction.TransactionType;
import bd.edu.uiu.unipay.user.Role;
import bd.edu.uiu.unipay.user.User;
import bd.edu.uiu.unipay.user.UserRepository;
import bd.edu.uiu.unipay.vendor.DynamicQrCache;
import bd.edu.uiu.unipay.vendor.VendorProfileRepository;
import bd.edu.uiu.unipay.wallet.Wallet;
import bd.edu.uiu.unipay.wallet.WalletRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The money-movement engine of UniPay — zero-fee P2P transfers (feature 4) and
 * zero-charge vendor QR payments (feature 3).
 *
 * <p><b>Concurrency contract</b> (mandatory course requirement):</p>
 * <ul>
 *   <li>Both wallets are locked with {@code @Lock(PESSIMISTIC_WRITE)}
 *       (SELECT ... FOR UPDATE) before any balance read/write — no double spend.</li>
 *   <li>Locks are always acquired in <b>ascending user-ID order</b>, so two
 *       opposite transfers between the same users at the same millisecond can
 *       never deadlock (proposal risk: "Database Deadlocks").</li>
 *   <li>Every payment key = {@code "TXN-" + client nonce} → repeated taps are
 *       idempotent (proposal risk: "Duplicate Payment Submission").</li>
 *   <li>The ledger row commits in the same ACID transaction as the balances;
 *       WebSocket alerts and audit writes are dispatched asynchronously
 *       <b>after commit</b> from the bounded thread pool.</li>
 * </ul>
 */
@Service
public class PaymentService {

    public static final String QR_PREFIX = "UNIPAY:VENDOR:";

    private final UserRepository users;
    private final WalletRepository wallets;
    private final TransactionRepository transactions;
    private final VendorProfileRepository vendorProfiles;
    private final DynamicQrCache dynamicQrCache;
    private final NotificationService notifications;
    private final AuditService audit;
    private final LoyaltyService loyaltyService;

    public PaymentService(UserRepository users,
                          WalletRepository wallets,
                          TransactionRepository transactions,
                          VendorProfileRepository vendorProfiles,
                          DynamicQrCache dynamicQrCache,
                          NotificationService notifications,
                          AuditService audit,
                          LoyaltyService loyaltyService) {
        this.users = users;
        this.wallets = wallets;
        this.transactions = transactions;
        this.vendorProfiles = vendorProfiles;
        this.dynamicQrCache = dynamicQrCache;
        this.notifications = notifications;
        this.audit = audit;
        this.loyaltyService = loyaltyService;
    }

    // ------------------------------------------------------------------ P2P

    /**
     * Zero-fee peer-to-peer transfer to any registered campus user, addressed
     * by University ID or phone number.
     */
    @Transactional
    public PaymentDtos.PaymentResponse p2pTransfer(String senderId, PaymentDtos.P2PTransferRequest request) {
        User sender = users.findById(senderId)
                .orElseThrow(() -> ApiException.unauthorized("Authenticated sender no longer exists."));

        String recipientKey = request.recipient().trim();
        User receiver = users.findById(recipientKey)
                .or(() -> users.findByPhoneNumber(recipientKey))
                .orElseThrow(() -> ApiException.notFound(
                        "No registered UniPay user with ID or phone: " + recipientKey));

        if (sender.getUserId().equals(receiver.getUserId())) {
            throw ApiException.badRequest("You cannot send money to yourself.");
        }

        String txnId = ledgerId(request.nonce());
        PaymentDtos.PaymentResponse existing = existingPayment(txnId, sender.getUserId());
        if (existing != null) {
            return existing;
        }

        Map<String, Wallet> locked = lockBothWallets(sender.getUserId(), receiver.getUserId());
        Wallet senderWallet = locked.get(sender.getUserId());
        Wallet receiverWallet = locked.get(receiver.getUserId());

        if (!senderWallet.hasAtLeast(request.amount())) {
            throw ApiException.conflict("Insufficient balance. Available ৳%s, tried to send ৳%s."
                    .formatted(senderWallet.getCurrentBalance().toPlainString(),
                               request.amount().toPlainString()));
        }

        senderWallet.debit(request.amount());
        receiverWallet.credit(request.amount());

        // Award loyalty points to the sender (0.25 % of transfer amount)
        loyaltyService.awardPoints(senderId, request.amount());

        Transaction txn = persist(txnId, sender, receiver, request.amount(), TransactionType.P2P_TRANSFER);

        TxCallbacks.afterCommit(() -> {
            notifications.pushP2pReceived(receiver, sender, txn);
            audit.logLedgerEntry(txn, sender.getUserId());
        });

        return response(txn, senderWallet.getCurrentBalance(), false);
    }

    // ---------------------------------------------------------- Vendor QR POS

    /**
     * Zero-charge merchant payment against a scanned static/dynamic QR payload
     * (or a manually entered vendor ID when no camera is available).
     */
    @Transactional
    public PaymentDtos.PaymentResponse vendorPayment(String payerId, PaymentDtos.VendorPaymentRequest request) {
        User payer = users.findById(payerId)
                .orElseThrow(() -> ApiException.unauthorized("Authenticated payer no longer exists."));

        ScannedQr qr = resolveQrTarget(request);   // parse only — no nonce consumption yet
        if (qr.vendorId().equals(payer.getUserId())) {
            throw ApiException.badRequest("You cannot pay your own stall.");
        }
        if (qr.presetAmount() != null && qr.presetAmount().compareTo(request.amount()) != 0) {
            throw ApiException.badRequest("Amount must be exactly ৳%s as preset on the dynamic QR."
                    .formatted(qr.presetAmount().toPlainString()));
        }

        User vendor = users.findById(qr.vendorId())
                .orElseThrow(() -> ApiException.notFound("Unknown vendor: " + qr.vendorId()));
        if (vendor.getRole() != Role.VENDOR) {
            throw ApiException.badRequest("Target account is not a vendor stall.");
        }
        vendorProfiles.findById(vendor.getUserId())
                .orElseThrow(() -> ApiException.badRequest("Vendor has not completed their stall profile yet."));

        String txnId = ledgerId(request.nonce());
        PaymentDtos.PaymentResponse existing = existingPayment(txnId, payer.getUserId());
        if (existing != null) {
            return existing;
        }

        Map<String, Wallet> locked = lockBothWallets(payer.getUserId(), vendor.getUserId());
        Wallet payerWallet = locked.get(payer.getUserId());
        Wallet vendorWallet = locked.get(vendor.getUserId());

        if (!payerWallet.hasAtLeast(request.amount())) {
            throw ApiException.conflict("Insufficient balance. Available ৳%s, tried to pay ৳%s."
                    .formatted(payerWallet.getCurrentBalance().toPlainString(),
                               request.amount().toPlainString()));
        }

        // Every check passed — redeem the one-time QR nonce only now, so a
        // rejected attempt (wrong amount, low balance) never burns the code.
        if (qr.nonce() != null && !dynamicQrCache.consume(qr.nonce(), qr.vendorId())) {
            throw ApiException.conflict(
                    "This dynamic QR has expired or was already used — ask the vendor to regenerate it.");
        }

        payerWallet.debit(request.amount());
        vendorWallet.credit(request.amount());

        // Award loyalty points to the payer (0.25 % of payment amount)
        loyaltyService.awardPoints(payerId, request.amount());

        Transaction txn = persist(txnId, payer, vendor, request.amount(), TransactionType.VENDOR_PAYMENT);

        TxCallbacks.afterCommit(() -> {
            notifications.pushVendorPaymentAlert(vendor, payer, txn); // POS banner + audio chime
            audit.logLedgerEntry(txn, payer.getUserId());
        });

        return response(txn, payerWallet.getCurrentBalance(), false);
    }

    // ------------------------------------------------------------- internals

    /** Parses a scanned QR payload (or manual vendor ID) and consumes one-time dynamic nonces. */
    private ScannedQr resolveQrTarget(PaymentDtos.VendorPaymentRequest request) {
        String payload = request.payload();
        if (payload != null && !payload.isBlank()) {
            // Case-sensitive values (nonces are UUIDs) — only the keywords are matched loosely.
            String[] parts = payload.trim().split(":");
            if (parts.length < 3
                    || !"UNIPAY".equalsIgnoreCase(parts[0])
                    || !"VENDOR".equalsIgnoreCase(parts[1])) {
                throw ApiException.badRequest("This QR code is not a UniPay vendor code.");
            }
            String vendorId = parts[2];
            BigDecimal presetAmount = null;
            String nonce = null;
            for (int i = 3; i + 1 < parts.length; i += 2) {
                if ("AMT".equalsIgnoreCase(parts[i])) {
                    presetAmount = parseAmount(parts[i + 1]);
                } else if ("NONCE".equalsIgnoreCase(parts[i])) {
                    nonce = parts[i + 1];
                }
            }
            return new ScannedQr(vendorId, presetAmount, nonce);
        }
        if (request.vendorId() != null && !request.vendorId().isBlank()) {
            return new ScannedQr(request.vendorId().trim(), null, null);
        }
        throw ApiException.badRequest("Provide the scanned QR payload or a vendor ID.");
    }

    private BigDecimal parseAmount(String raw) {
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException ex) {
            throw ApiException.badRequest("Malformed amount inside the QR code.");
        }
    }

    /**
     * Acquires PESSIMISTIC_WRITE locks on both wallets in <b>ascending
     * user-ID order</b> — the deterministic global lock ordering that
     * eliminates the ABBA deadlock between simultaneous opposite transfers.
     */
    private Map<String, Wallet> lockBothWallets(String userIdA, String userIdB) {
        String first = userIdA.compareTo(userIdB) <= 0 ? userIdA : userIdB;
        String second = first.equals(userIdA) ? userIdB : userIdA;

        Wallet firstWallet = wallets.findWalletForUpdateByUserId(first)
                .orElseThrow(() -> ApiException.notFound("Wallet not found for user " + first));
        Wallet secondWallet = wallets.findWalletForUpdateByUserId(second)
                .orElseThrow(() -> ApiException.notFound("Wallet not found for user " + second));

        Map<String, Wallet> byUserId = new HashMap<>();
        byUserId.put(firstWallet.getUser().getUserId(), firstWallet);
        byUserId.put(secondWallet.getUser().getUserId(), secondWallet);
        return byUserId;
    }

    /** Ledger id = TXN-&lt;nonce&gt;. The unique PK turns duplicate submissions into no-ops. */
    private String ledgerId(String nonce) {
        return "TXN-" + nonce.trim();
    }

    private PaymentDtos.PaymentResponse existingPayment(String txnId, String payerId) {
        return transactions.findById(txnId)
                .map(txn -> response(txn, currentBalanceOf(payerId), true))
                .orElse(null);
    }

    private BigDecimal currentBalanceOf(String userId) {
        return wallets.findByUser_UserId(userId)
                .map(Wallet::getCurrentBalance)
                .orElse(BigDecimal.ZERO);
    }

    /**
     * Saves the ledger row; a concurrent request with the same nonce loses the
     * PK race and transparently receives the winner's row (idempotency).
     */
    private Transaction persist(String txnId, User sender, User receiver,
                                BigDecimal amount, TransactionType type) {
        try {
            return transactions.save(new Transaction(txnId, sender, receiver, amount, type));
        } catch (DataIntegrityViolationException race) {
            return transactions.findById(txnId)
                    .orElseThrow(() -> race);
        }
    }

    private PaymentDtos.PaymentResponse response(Transaction txn, BigDecimal payerBalance, boolean duplicate) {
        return new PaymentDtos.PaymentResponse(
                txn.getTransactionId(),
                txn.getTransactionType().name(),
                txn.getAmount(),
                txn.getSender().getUserId(),
                txn.getReceiver().getUserId(),
                txn.getReceiver().getFullName(),
                payerBalance,
                txn.getTimestamp() == null ? java.time.Instant.now().toString() : txn.getTimestamp().toString(),
                duplicate);
    }

    /** Parsed QR target: vendor id, optional preset amount, optional one-time nonce. */
    private record ScannedQr(String vendorId, BigDecimal presetAmount, String nonce) {
    }
}
