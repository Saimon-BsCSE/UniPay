package bd.edu.uiu.unipay.bkash;

import bd.edu.uiu.unipay.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Tracks in-flight bKash checkout sessions between {@code createPayment} and
 * {@code executePayment}. A row is inserted when the user is redirected to the
 * bKash payment page (PENDING), then updated to COMPLETED or FAILED once the
 * bKash callback is processed.
 *
 * <p>Using a durable DB table (rather than an in-memory map) means the session
 * survives a server restart and works for both the H2 demo and MySQL profiles.</p>
 */
@Entity
@Table(name = "bkash_pending_payments")
public class BkashPendingPayment {

    public enum Status { PENDING, COMPLETED, FAILED }

    @Id
    @Column(name = "payment_id", length = 100)
    private String paymentId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    /** Client-generated idempotency nonce (used to build the final Transaction PK). */
    @Column(name = "nonce", nullable = false, length = 100)
    private String nonce;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.PENDING;

    @CreationTimestamp
    @Column(name = "created_at")
    private Instant createdAt;

    protected BkashPendingPayment() {} // JPA

    public BkashPendingPayment(String paymentId, User user, BigDecimal amount, String nonce) {
        this.paymentId = paymentId;
        this.user      = user;
        this.amount    = amount;
        this.nonce     = nonce;
        this.status    = Status.PENDING;
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public String   getPaymentId()  { return paymentId; }
    public User     getUser()       { return user; }
    public BigDecimal getAmount()   { return amount; }
    public String   getNonce()      { return nonce; }
    public Status   getStatus()     { return status; }
    public Instant  getCreatedAt()  { return createdAt; }

    // ── Status transitions ────────────────────────────────────────────────────

    public void markCompleted() { this.status = Status.COMPLETED; }
    public void markFailed()    { this.status = Status.FAILED; }
}
