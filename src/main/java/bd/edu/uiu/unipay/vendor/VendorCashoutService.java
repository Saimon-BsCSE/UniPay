package bd.edu.uiu.unipay.vendor;

import bd.edu.uiu.unipay.audit.AuditService;
import bd.edu.uiu.unipay.common.ApiException;
import bd.edu.uiu.unipay.common.TxCallbacks;
import bd.edu.uiu.unipay.notification.NotificationService;
import bd.edu.uiu.unipay.notification.WsEvent;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Service for campus vendors to withdraw/cash out their earnings to MFS (bKash, Nagad, Rocket)
 * or Bank Account with pessimistic row locking and audit logging.
 */
@Service
public class VendorCashoutService {

    private static final Logger log = LoggerFactory.getLogger(VendorCashoutService.class);
    private static final Pattern MFS_PHONE_PATTERN = Pattern.compile("^01[3-9]\\d{8,9}$");

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final VendorCashoutRepository cashoutRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final AuditService auditService;

    public VendorCashoutService(UserRepository userRepository,
                                WalletRepository walletRepository,
                                TransactionRepository transactionRepository,
                                VendorCashoutRepository cashoutRepository,
                                SimpMessagingTemplate messagingTemplate,
                                AuditService auditService) {
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
        this.cashoutRepository = cashoutRepository;
        this.messagingTemplate = messagingTemplate;
        this.auditService = auditService;
    }

    /**
     * Executes a vendor cashout/withdrawal to MFS or Bank Account.
     */
    @Transactional
    public VendorCashoutDtos.CashoutResponse cashout(String vendorId, VendorCashoutDtos.CashoutRequest request) {
        User vendor = userRepository.findById(vendorId)
                .orElseThrow(() -> ApiException.unauthorized("Authenticated vendor not found."));

        if (vendor.getRole() != Role.VENDOR) {
            throw ApiException.forbidden("Cash-out is exclusively available for campus vendor stalls.");
        }

        if (request.amount() == null || request.amount().compareTo(new BigDecimal("10.00")) < 0) {
            throw ApiException.badRequest("Minimum cash-out amount is ৳10.00.");
        }

        // Validate destination credentials
        String accountNumber = request.accountNumber() == null ? "" : request.accountNumber().trim();
        if (accountNumber.isBlank()) {
            throw ApiException.badRequest("Account number or phone number is required.");
        }

        if (request.channel() == CashoutChannel.BKASH || request.channel() == CashoutChannel.NAGAD || request.channel() == CashoutChannel.ROCKET) {
            if (!MFS_PHONE_PATTERN.matcher(accountNumber).matches()) {
                throw ApiException.badRequest("Invalid %s account number. Must be a valid 11-digit Bangladeshi mobile number (e.g. 017XXXXXXXX)."
                        .formatted(request.channel()));
            }
        } else if (request.channel() == CashoutChannel.BANK) {
            if (request.bankName() == null || request.bankName().trim().isBlank()) {
                throw ApiException.badRequest("Bank name is required for bank withdraw.");
            }
            if (request.accountHolderName() == null || request.accountHolderName().trim().isBlank()) {
                throw ApiException.badRequest("Account holder name is required for bank withdraw.");
            }
            if (accountNumber.length() < 6) {
                throw ApiException.badRequest("Bank account number must be at least 6 digits.");
            }
        }

        // Concurrency guarantee: Acquire pessimistic row lock on vendor wallet
        Wallet wallet = walletRepository.findWalletForUpdateByUserId(vendorId)
                .orElseThrow(() -> ApiException.notFound("Wallet not found for vendor " + vendorId));

        if (!wallet.hasAtLeast(request.amount())) {
            throw ApiException.conflict("Insufficient wallet balance. Available: ৳%s, requested cash-out: ৳%s."
                    .formatted(wallet.getCurrentBalance().toPlainString(), request.amount().toPlainString()));
        }

        // Debit vendor wallet
        wallet.debit(request.amount());

        String nonce = request.nonce() != null && !request.nonce().isBlank()
                ? request.nonce().trim() : UUID.randomUUID().toString();
        String txnId = "TXN-CSH-" + nonce;

        Transaction txn = persist(txnId, vendor, vendor, request.amount(), TransactionType.VENDOR_CASHOUT);

        String cashoutId = "CSH-" + UUID.randomUUID().toString().substring(0, 12);
        VendorCashout cashout = new VendorCashout(
                cashoutId,
                vendor,
                request.amount(),
                request.channel(),
                accountNumber,
                request.accountHolderName() == null ? "" : request.accountHolderName().trim(),
                request.bankName() == null ? "" : request.bankName().trim(),
                request.branchName() == null ? "" : request.branchName().trim(),
                txn.getTransactionId()
        );
        cashoutRepository.save(cashout);

        String destinationStr = request.channel() == CashoutChannel.BANK
                ? "%s (A/C: %s, Holder: %s)".formatted(request.bankName().trim(), accountNumber, request.accountHolderName().trim())
                : "%s (%s)".formatted(request.channel(), accountNumber);

        // Post-commit notifications and audit trail
        TxCallbacks.afterCommit(() -> {
            WsEvent event = WsEvent.of(
                    "VENDOR_CASHOUT",
                    "Cash-out Successful",
                    "৳%s withdrawn to %s".formatted(request.amount().toPlainString(), destinationStr),
                    request.amount(),
                    request.channel().name(),
                    txn.getTransactionId()
            );
            messagingTemplate.convertAndSend(NotificationService.TOPIC_VENDOR_POS + vendorId, event);
            auditService.logLedgerEntry(txn, vendorId);
        });

        return new VendorCashoutDtos.CashoutResponse(
                cashout.getCashoutId(),
                txn.getTransactionId(),
                request.amount(),
                request.channel(),
                destinationStr,
                wallet.getCurrentBalance(),
                Instant.now().toString(),
                "Successfully withdrawn ৳%s via %s. Funds will reflect in your account."
                        .formatted(request.amount().toPlainString(), destinationStr)
        );
    }

    /**
     * Retrieves cash-out history for the vendor as safe serialisable DTOs
     * (avoids Jackson/Hibernate lazy-load proxy exception on the User field).
     */
    @Transactional(readOnly = true)
    public List<VendorDtos.CashoutDto> getHistory(String vendorId) {
        return cashoutRepository.findByVendor_UserIdOrderByCreatedAtDesc(vendorId)
                .stream()
                .map(c -> new VendorDtos.CashoutDto(
                        c.getCashoutId(),
                        vendorId,
                        c.getAmount(),
                        c.getChannel() != null ? c.getChannel().name() : null,
                        c.getAccountNumber(),
                        c.getAccountHolderName(),
                        c.getBankName(),
                        c.getBranchName(),
                        c.getTransactionId(),
                        c.getStatus(),
                        c.getCreatedAt() != null ? c.getCreatedAt().toString() : null
                ))
                .toList();
    }

    private Transaction persist(String txnId, User sender, User receiver, BigDecimal amount, TransactionType type) {
        try {
            return transactionRepository.save(new Transaction(txnId, sender, receiver, amount, type));
        } catch (DataIntegrityViolationException race) {
            return transactionRepository.findById(txnId).orElseThrow(() -> race);
        }
    }
}
