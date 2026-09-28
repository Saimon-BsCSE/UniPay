package bd.edu.uiu.unipay.transaction;

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
 * Maps the {@code Transactions} table — the central, append-only ledger of the
 * enclosed economy. The primary key is {@code "TXN-" + client idempotency nonce},
 * which makes repeated taps of a payment button under flaky mobile networks
 * collapse into a single ledger entry (proposal risk: duplicate submission).
 */
@Entity
@Table(name = "Transactions")
public class Transaction {

    @Id
    @Column(name = "transaction_id", length = 100)
    private String transactionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sender_id", nullable = false)
    private User sender;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "receiver_id", nullable = false)
    private User receiver;

    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 30)
    private TransactionType transactionType;

    @Column(name = "timestamp")
    private Instant timestamp;

    @jakarta.persistence.PrePersist
    protected void onCreate() {
        if (this.timestamp == null) {
            this.timestamp = Instant.now();
        }
    }

    protected Transaction() {
        // JPA
    }

    public Transaction(String transactionId, User sender, User receiver,
                       BigDecimal amount, TransactionType transactionType) {
        this.transactionId = transactionId;
        this.sender = sender;
        this.receiver = receiver;
        this.amount = amount;
        this.transactionType = transactionType;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public User getSender() {
        return sender;
    }

    public User getReceiver() {
        return receiver;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public TransactionType getTransactionType() {
        return transactionType;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    /**
     * Used only by {@code DataSeeder} to backdate historical demo transactions.
     * Never call this from production code.
     */
    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }
}

