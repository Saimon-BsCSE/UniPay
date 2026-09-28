package bd.edu.uiu.unipay.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import bd.edu.uiu.unipay.user.User;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Maps the {@code Wallets} table — one wallet per user, the primary store of
 * value inside the enclosed campus economy (no cash-out is possible).
 */
@Entity
@Table(name = "Wallets")
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "wallet_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "current_balance", nullable = false, precision = 10, scale = 2)
    private BigDecimal currentBalance = BigDecimal.ZERO;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    protected Wallet() {
        // JPA
    }

    public Wallet(User user) {
        this.user = user;
        this.currentBalance = BigDecimal.ZERO;
    }

    /** Adds money to the wallet (MFS cash-in / incoming transfer / sale). */
    public void credit(BigDecimal amount) {
        this.currentBalance = this.currentBalance.add(amount);
    }

    /** Removes money from the wallet. Caller must check sufficiency first. */
    public void debit(BigDecimal amount) {
        this.currentBalance = this.currentBalance.subtract(amount);
    }

    public boolean hasAtLeast(BigDecimal amount) {
        return currentBalance.compareTo(amount) >= 0;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public BigDecimal getCurrentBalance() {
        return currentBalance;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
