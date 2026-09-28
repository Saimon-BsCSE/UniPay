package bd.edu.uiu.unipay.wallet;

import java.math.BigDecimal;
import java.util.List;

/**
 * Read-only wallet and ledger projections.
 */
public final class WalletDtos {

    private WalletDtos() {
    }

    public record WalletResponse(String userId,
                                 String fullName,
                                 String role,
                                 BigDecimal balance,
                                 String updatedAt,
                                 BigDecimal loyaltyPoints) {
    }

    /** direction: IN | OUT — relative to the requesting user. */
    public record TransactionDto(String transactionId,
                                 String type,
                                 BigDecimal amount,
                                 String direction,
                                 String counterpartyId,
                                 String counterpartyName,
                                 String timestamp) {
    }

    /** One data point in a time-series chart (date label + amount). */
    public record DailyPoint(String date, BigDecimal amount) {}

    /**
     * Expense/earnings summary for the tracker chart.
     *
     * @param period   "DAILY" | "WEEKLY" | "MONTHLY"
     * @param label    human-readable label (e.g. "Last 7 Days")
     * @param total    total amount for the period
     * @param points   ordered list of daily data points
     * @param isVendor true when the caller is a VENDOR (earnings not expenses)
     */
    public record ExpenseSummaryResponse(String period,
                                         String label,
                                         BigDecimal total,
                                         List<DailyPoint> points,
                                         boolean isVendor) {}
}
