package bd.edu.uiu.unipay.splitpay;

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
 * An individual payment request generated for a participant of a SplitBill.
 */
@Entity
@Table(name = "split_requests")
public class SplitRequest {

    @Id
    @Column(name = "request_id", length = 50)
    private String requestId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bill_id", nullable = false)
    private SplitBill bill;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "participant_id", nullable = false)
    private User participant;

    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SplitRequestStatus status;

    @Column(name = "transaction_id", length = 100)
    private String transactionId;

    @Column(name = "paid_at")
    private Instant paidAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    protected SplitRequest() {
        // JPA
    }

    public SplitRequest(String requestId, SplitBill bill, User participant, BigDecimal amount) {
        this.requestId = requestId;
        this.bill = bill;
        this.participant = participant;
        this.amount = amount;
        this.status = SplitRequestStatus.PENDING;
    }

    public String getRequestId() {
        return requestId;
    }

    public SplitBill getBill() {
        return bill;
    }

    void setBill(SplitBill bill) {
        this.bill = bill;
    }

    public User getParticipant() {
        return participant;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public SplitRequestStatus getStatus() {
        return status;
    }

    public void setStatus(SplitRequestStatus status) {
        this.status = status;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public void setPaidAt(Instant paidAt) {
        this.paidAt = paidAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
