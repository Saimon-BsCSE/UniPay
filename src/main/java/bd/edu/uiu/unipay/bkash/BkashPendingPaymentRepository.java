package bd.edu.uiu.unipay.bkash;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/**
 * Spring Data repository for {@link BkashPendingPayment} sessions.
 */
public interface BkashPendingPaymentRepository extends JpaRepository<BkashPendingPayment, String> {

    /**
     * Finds stale PENDING sessions older than the given threshold.
     * Useful for a scheduled reconciliation job that marks truly abandoned
     * payments as FAILED after bKash's own session timeout (~30 minutes).
     */
    @Query("SELECT p FROM BkashPendingPayment p WHERE p.status = 'PENDING' AND p.createdAt < :threshold")
    List<BkashPendingPayment> findStalePending(@Param("threshold") Instant threshold);
}
