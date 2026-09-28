package bd.edu.uiu.unipay.transaction;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, String> {

    Page<Transaction> findBySender_UserIdOrReceiver_UserIdOrderByTimestampDesc(
            String senderId, String receiverId, Pageable pageable);

    /** End-of-day sales figure for a vendor dashboard (zero-fee vendor payments received). */
    @Query("""
            select coalesce(sum(t.amount), 0) from Transaction t
            where t.receiver.userId = :vendorId
              and t.transactionType = bd.edu.uiu.unipay.transaction.TransactionType.VENDOR_PAYMENT
              and t.timestamp >= :since
            """)
    BigDecimal sumVendorPaymentsSince(@Param("vendorId") String vendorId, @Param("since") Instant since);

    @Query("""
            select count(t) from Transaction t
            where t.receiver.userId = :vendorId
              and t.transactionType = bd.edu.uiu.unipay.transaction.TransactionType.VENDOR_PAYMENT
              and t.timestamp >= :since
            """)
    long countVendorPaymentsSince(@Param("vendorId") String vendorId, @Param("since") Instant since);

    /**
     * Daily outgoing expense aggregates for a user (non-vendor).
     * Returns Object[]{date_string, total_amount} ordered by date ascending.
     * Only counts VENDOR_PAYMENT and P2P_TRANSFER and SPLIT_PAY as expenses.
     */
    @Query("""
            select function('DATE', t.timestamp), coalesce(sum(t.amount), 0)
            from Transaction t
            where t.sender.userId = :userId
              and t.transactionType in (
                  bd.edu.uiu.unipay.transaction.TransactionType.VENDOR_PAYMENT,
                  bd.edu.uiu.unipay.transaction.TransactionType.P2P_TRANSFER,
                  bd.edu.uiu.unipay.transaction.TransactionType.SPLIT_PAY
              )
              and t.timestamp >= :since
            group by function('DATE', t.timestamp)
            order by function('DATE', t.timestamp) asc
            """)
    List<Object[]> dailyExpensesSince(@Param("userId") String userId, @Param("since") Instant since);

    /**
     * Daily incoming earnings aggregates for a vendor.
     * Returns Object[]{date_string, total_amount} ordered by date ascending.
     */
    @Query("""
            select function('DATE', t.timestamp), coalesce(sum(t.amount), 0)
            from Transaction t
            where t.receiver.userId = :vendorId
              and t.transactionType = bd.edu.uiu.unipay.transaction.TransactionType.VENDOR_PAYMENT
              and t.timestamp >= :since
            group by function('DATE', t.timestamp)
            order by function('DATE', t.timestamp) asc
            """)
    List<Object[]> dailyEarningsSince(@Param("vendorId") String vendorId, @Param("since") Instant since);
}
