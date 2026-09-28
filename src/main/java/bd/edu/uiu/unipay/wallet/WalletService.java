package bd.edu.uiu.unipay.wallet;

import bd.edu.uiu.unipay.common.ApiException;
import bd.edu.uiu.unipay.loyalty.LoyaltyService;
import bd.edu.uiu.unipay.transaction.Transaction;
import bd.edu.uiu.unipay.transaction.TransactionRepository;
import bd.edu.uiu.unipay.user.Role;
import bd.edu.uiu.unipay.user.User;
import bd.edu.uiu.unipay.user.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class WalletService {

    private final WalletRepository wallets;
    private final UserRepository users;
    private final TransactionRepository transactions;
    private final LoyaltyService loyaltyService;

    public WalletService(WalletRepository wallets, UserRepository users,
                         TransactionRepository transactions, LoyaltyService loyaltyService) {
        this.wallets = wallets;
        this.users = users;
        this.transactions = transactions;
        this.loyaltyService = loyaltyService;
    }

    @Transactional(readOnly = true)
    public WalletDtos.WalletResponse getWallet(String userId) {
        User user = users.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found: " + userId));
        Wallet wallet = wallets.findByUser_UserId(userId)
                .orElseThrow(() -> ApiException.notFound("Wallet not found for " + userId));
        return new WalletDtos.WalletResponse(
                user.getUserId(),
                user.getFullName(),
                user.getRole().name(),
                wallet.getCurrentBalance(),
                wallet.getUpdatedAt() == null ? null : wallet.getUpdatedAt().toString(),
                loyaltyService.getBalance(userId).points());
    }

    /** Personal ledger view: newest first, with counterparty names resolved. */
    @Transactional(readOnly = true)
    public Page<WalletDtos.TransactionDto> history(String userId, Pageable pageable) {
        return transactions
                .findBySender_UserIdOrReceiver_UserIdOrderByTimestampDesc(userId, userId, pageable)
                .map(txn -> toDto(txn, userId));
    }

    static WalletDtos.TransactionDto toDto(Transaction txn, String viewerId) {
        // A cash-in top-up is booked self-to-self (sender == receiver == user). Testing only
        // "is the viewer the sender?" therefore classified every top-up as OUT and rendered it
        // as a negative amount, so a self-credit counts as money arriving instead.
        boolean selfCredit = txn.getSender().getUserId().equals(txn.getReceiver().getUserId());
        boolean outgoing = !selfCredit && txn.getSender().getUserId().equals(viewerId);
        User counterparty = outgoing ? txn.getReceiver() : txn.getSender();
        return new WalletDtos.TransactionDto(
                txn.getTransactionId(),
                txn.getTransactionType().name(),
                txn.getAmount(),
                outgoing ? "OUT" : "IN",
                counterparty.getUserId(),
                counterparty.getFullName(),
                txn.getTimestamp() == null ? null : txn.getTimestamp().toString());
    }

    @Transactional(readOnly = true)
    public BigDecimal currentBalance(String userId) {
        return wallets.findByUser_UserId(userId)
                .map(Wallet::getCurrentBalance)
                .orElse(BigDecimal.ZERO);
    }

    /**
     * Returns a daily-bucketed expense or earnings summary for the expense tracker.
     *
     * @param userId the authenticated user
     * @param period "DAILY" (last 1 day), "WEEKLY" (last 7 days), or "MONTHLY" (last 30 days)
     */
    @Transactional(readOnly = true)
    public WalletDtos.ExpenseSummaryResponse expenseSummary(String userId, String period) {
        User user = users.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found: " + userId));
        boolean isVendor = user.getRole() == Role.VENDOR;

        int days = switch (period.toUpperCase()) {
            case "DAILY"   -> 1;
            case "WEEKLY"  -> 7;
            default        -> 30;  // MONTHLY
        };
        String label = switch (period.toUpperCase()) {
            case "DAILY"   -> "Today";
            case "WEEKLY"  -> "Last 7 Days";
            default        -> "Last 30 Days";
        };

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate from  = today.minusDays(days - 1L);
        Instant since   = from.atStartOfDay(ZoneOffset.UTC).toInstant();

        // Fetch raw aggregates from DB
        List<Object[]> raw = isVendor
                ? transactions.dailyEarningsSince(userId, since)
                : transactions.dailyExpensesSince(userId, since);

        // Build a complete, gapless date map (fills zeros for days with no transactions)
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        DateTimeFormatter fmt = DateTimeFormatter.ISO_LOCAL_DATE;
        for (long i = 0; i < days; i++) {
            map.put(from.plusDays(i).format(fmt), BigDecimal.ZERO);
        }
        for (Object[] row : raw) {
            String dateStr = row[0].toString();   // DATE() returns java.sql.Date or String
            BigDecimal amt = new BigDecimal(row[1].toString());
            map.merge(dateStr, amt, BigDecimal::add);
        }

        List<WalletDtos.DailyPoint> points = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> entry : map.entrySet()) {
            points.add(new WalletDtos.DailyPoint(entry.getKey(), entry.getValue()));
            total = total.add(entry.getValue());
        }

        return new WalletDtos.ExpenseSummaryResponse(period.toUpperCase(), label, total, points, isVendor);
    }
}

