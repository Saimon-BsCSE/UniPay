package bd.edu.uiu.unipay.mfs;

import bd.edu.uiu.unipay.audit.AuditService;
import bd.edu.uiu.unipay.common.ApiException;
import bd.edu.uiu.unipay.common.TxCallbacks;
import bd.edu.uiu.unipay.loyalty.LoyaltyService;
import bd.edu.uiu.unipay.notification.NotificationService;
import bd.edu.uiu.unipay.payment.PaymentDtos;
import bd.edu.uiu.unipay.transaction.Transaction;
import bd.edu.uiu.unipay.transaction.TransactionRepository;
import bd.edu.uiu.unipay.transaction.TransactionType;
import bd.edu.uiu.unipay.user.User;
import bd.edu.uiu.unipay.user.UserRepository;
import bd.edu.uiu.unipay.wallet.Wallet;
import bd.edu.uiu.unipay.wallet.WalletRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

/**
 * MFS Online Cash-In Gateway (feature 2): simulated bKash / Nagad / Rocket
 * sandboxes add funds to the primary wallet with <b>zero service charge</b>.
 * There is deliberately no reverse operation — cash-out is disabled
 * (feature 5: enclosed ecosystem).
 */
@Service
public class MfsService {

    private final MfsGatewayFactory gatewayFactory;
    private final UserRepository users;
    private final WalletRepository wallets;
    private final TransactionRepository transactions;
    private final NotificationService notifications;
    private final AuditService audit;
    private final LoyaltyService loyaltyService;

    public MfsService(MfsGatewayFactory gatewayFactory,
                      UserRepository users,
                      WalletRepository wallets,
                      TransactionRepository transactions,
                      NotificationService notifications,
                      AuditService audit,
                      LoyaltyService loyaltyService) {
        this.gatewayFactory = gatewayFactory;
        this.users = users;
        this.wallets = wallets;
        this.transactions = transactions;
        this.notifications = notifications;
        this.audit = audit;
        this.loyaltyService = loyaltyService;
    }

    public List<MfsDtos.ProviderDto> providers() {
        return Arrays.stream(MfsProvider.values())
                .map(p -> new MfsDtos.ProviderDto(p.name(), p.getDisplayName(), p.getIcon(), p.getLogoUrl(),
                        p.getMinAmount(), p.getMaxAmount()))
                .toList();
    }

    @Transactional
    public PaymentDtos.PaymentResponse cashIn(String userId, MfsDtos.CashInRequest request) {
        MfsProvider provider = MfsProvider.fromCode(request.provider());
        if (provider == null) {
            throw ApiException.badRequest("Unsupported MFS provider: " + request.provider()
                    + " (supported: BKASH, NAGAD, ROCKET)");
        }
        gatewayFactory.get(provider).verify(request.mfsPhoneNumber(), request.otp(), request.amount());

        User user = users.findById(userId)
                .orElseThrow(() -> ApiException.unauthorized("Authenticated user no longer exists."));

        // Idempotency: the ledger key TXN-<nonce> makes duplicate submissions no-ops.
        String txnId = "TXN-" + request.nonce().trim();
        if (transactions.existsById(txnId)) {
            throw ApiException.conflict("This cash-in was already processed (duplicate nonce).");
        }

        Wallet wallet = wallets.findWalletForUpdateByUserId(userId)
                .orElseThrow(() -> ApiException.notFound("Wallet not found for user " + userId));

        wallet.credit(request.amount());
        wallets.save(wallet);

        // Award bonus loyalty points on cash-in top up
        loyaltyService.awardPoints(userId, request.amount());

        Transaction txn;
        try {
            txn = transactions.save(new Transaction(
                    txnId, user, user, request.amount(), TransactionType.MFS_CASH_IN));
        } catch (DataIntegrityViolationException race) {
            // concurrent duplicate nonce lost the PK race — undo nothing, fail clean
            throw ApiException.conflict("This cash-in was already processed (duplicate nonce).");
        }

        Transaction committed = txn;
        TxCallbacks.afterCommit(() -> {
            notifications.pushCashInCompleted(user, committed);
            audit.logLedgerEntry(committed, userId);
        });

        return new PaymentDtos.PaymentResponse(
                committed.getTransactionId(),
                committed.getTransactionType().name(),
                committed.getAmount(),
                userId,
                userId,
                provider.getDisplayName() + " cash-in",
                wallet.getCurrentBalance(),
                java.time.Instant.now().toString(),
                false);
    }
}
