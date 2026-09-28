package bd.edu.uiu.unipay.loyalty;

import bd.edu.uiu.unipay.common.ApiException;
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

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Loyalty Points Service — earns, tracks and redeems bonus points.
 *
 * <h3>Earning Rule</h3>
 * Every VENDOR_PAYMENT, P2P_TRANSFER, and SPLIT_PAY awards
 * <b>0.25 % of the transaction amount</b> as bonus points
 * (e.g., spending ৳1,000 → 2.5 points).
 *
 * <h3>Redemption Rule</h3>
 * Minimum redemption threshold is {@value #MIN_REDEMPTION_POINTS} points.
 * Upon redemption, 1 point = ৳1 is credited to the user's wallet atomically
 * within a single database transaction.
 */
@Service
public class LoyaltyService {

    /** Earn rate: 0.25 % of the transaction amount. */
    private static final BigDecimal EARN_RATE = new BigDecimal("0.0025");

    /** Minimum points balance required to redeem (10 points = ৳10). */
    public static final BigDecimal MIN_REDEMPTION_POINTS = new BigDecimal("10");

    /** Exchange rate: 1 point = ৳1 cash value. */
    private static final BigDecimal CONVERSION_RATE = BigDecimal.ONE;

    private final LoyaltyRepository loyaltyRepository;
    private final WalletRepository wallets;
    private final TransactionRepository transactions;
    private final UserRepository users;

    public LoyaltyService(LoyaltyRepository loyaltyRepository,
                          WalletRepository wallets,
                          TransactionRepository transactions,
                          UserRepository users) {
        this.loyaltyRepository = loyaltyRepository;
        this.wallets = wallets;
        this.transactions = transactions;
        this.users = users;
    }

    // ---------------------------------------------------------------- award

    /**
     * Awards loyalty points for a spend transaction.  Must be called inside
     * the caller's {@code @Transactional} scope so the point award is atomic
     * with the wallet debit.
     *
     * @param userId            the spending user's ID
     * @param transactionAmount the gross amount spent
     */
    public void awardPoints(String userId, BigDecimal transactionAmount) {
        BigDecimal earned = transactionAmount
                .multiply(EARN_RATE)
                .setScale(4, RoundingMode.HALF_UP);
        if (earned.compareTo(BigDecimal.ZERO) <= 0) return;

        LoyaltyAccount account = loyaltyRepository.findByUserId(userId)
                .orElseGet(() -> loyaltyRepository.save(new LoyaltyAccount(userId)));
        account.addPoints(earned);
        loyaltyRepository.save(account);
    }

    // ---------------------------------------------------------------- balance

    /**
     * Returns the current loyalty point balance for the given user, or
     * {@code 0} if the user has no loyalty account yet.
     */
    @Transactional(readOnly = true)
    public LoyaltyDtos.BalanceResponse getBalance(String userId) {
        BigDecimal points = loyaltyRepository.findByUserId(userId)
                .map(LoyaltyAccount::getTotalPoints)
                .orElse(BigDecimal.ZERO);
        boolean eligible = points.compareTo(MIN_REDEMPTION_POINTS) >= 0;
        return new LoyaltyDtos.BalanceResponse(points, MIN_REDEMPTION_POINTS, eligible);
    }

    // ---------------------------------------------------------------- redeem

    /**
     * Atomically redeems all of the user's loyalty points by:
     * <ol>
     *   <li>Acquiring a PESSIMISTIC_WRITE lock on the loyalty row.</li>
     *   <li>Checking the 100-point threshold.</li>
     *   <li>Deducting the points.</li>
     *   <li>Crediting the equivalent ৳ amount to the wallet.</li>
     *   <li>Writing a {@code LOYALTY_REDEMPTION} ledger entry.</li>
     * </ol>
     *
     * @throws ApiException 402 if the balance is below the minimum threshold
     */
    @Transactional
    public LoyaltyDtos.RedemptionResponse redeem(String userId) {
        LoyaltyAccount account = loyaltyRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> ApiException.badRequest(
                        "No loyalty points balance found. Make purchases first to earn points."));

        if (!account.hasAtLeast(MIN_REDEMPTION_POINTS)) {
            throw new ApiException(402,
                    "Insufficient loyalty points. You need at least " + MIN_REDEMPTION_POINTS.toPlainString()
                    + " coins to redeem (1 coin = ৳1). Your balance: " + account.getTotalPoints().toPlainString() + " pts.");
        }

        BigDecimal pointsToRedeem = account.getTotalPoints();
        // Conversion: 1 pt = ৳1
        BigDecimal cashValue = pointsToRedeem
                .multiply(CONVERSION_RATE)
                .setScale(2, RoundingMode.HALF_UP);

        account.deductPoints(pointsToRedeem);
        loyaltyRepository.save(account);

        // Credit wallet
        Wallet wallet = wallets.findWalletForUpdateByUserId(userId)
                .orElseThrow(() -> ApiException.notFound("Wallet not found for user: " + userId));
        wallet.credit(cashValue);

        // Write a ledger entry so the redemption appears in transaction history
        User user = users.findById(userId)
                .orElseThrow(() -> ApiException.unauthorized("User not found: " + userId));
        String txnId = "LPT-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 18);
        try {
            transactions.save(new Transaction(txnId, user, user, cashValue, TransactionType.LOYALTY_REDEMPTION));
        } catch (DataIntegrityViolationException ignore) { /* idempotency */ }

        return new LoyaltyDtos.RedemptionResponse(
                pointsToRedeem,
                cashValue,
                wallet.getCurrentBalance(),
                account.getTotalPoints(),
                "🎁 " + pointsToRedeem.toPlainString() + " points redeemed for ৳" + cashValue.toPlainString() + "!"
        );
    }
}
