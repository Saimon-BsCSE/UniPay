package bd.edu.uiu.unipay.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Persisted notification entity for the user notification center.
 */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 50)
    private String userId;

    @Column(name = "type", nullable = false, length = 50)
    private String type;

    @Column(name = "title", nullable = false, length = 150)
    private String title;

    @Column(name = "message", nullable = false, length = 500)
    private String message;

    @Column(name = "amount", precision = 10, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(name = "sender_name", length = 100)
    private String senderName;

    @Column(name = "transaction_id", length = 100)
    private String transactionId;

    @Column(name = "is_read", nullable = false)
    private boolean isRead = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Notification() {
        // JPA
    }

    public Notification(String userId, String type, String title, String message,
                        BigDecimal amount, String senderName, String transactionId) {
        this.userId = userId;
        this.type = type;
        this.title = title;
        this.message = message;
        this.amount = amount != null ? amount : BigDecimal.ZERO;
        this.senderName = senderName;
        this.transactionId = transactionId;
        this.isRead = false;
        this.createdAt = Instant.now();
    }

    public Notification(String userId, String type, String title, String message,
                        BigDecimal amount, String senderName, String transactionId, Instant createdAt) {
        this.userId = userId;
        this.type = type;
        this.title = title;
        this.message = message;
        this.amount = amount != null ? amount : BigDecimal.ZERO;
        this.senderName = senderName;
        this.transactionId = transactionId;
        this.isRead = false;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public String getMessage() {
        return message;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getSenderName() {
        return senderName;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public boolean isRead() {
        return isRead;
    }

    public void setRead(boolean read) {
        this.isRead = read;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
