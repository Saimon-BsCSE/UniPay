package bd.edu.uiu.unipay.loyalty;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * Maps the {@code loyalty_points} table — one row per user, storing a
 * running total of earned bonus points (0.25 % of every spend transaction).
 *
 * <p>Points accumulate as a {@code DECIMAL(12,4)} to preserve fractional
 * precision (e.g., spending ৳1,000 earns exactly 2.5000 points).</p>
 *
 * <p>1 point redeems for ৳1 of wallet credit.</p>
 */
@Entity
@Table(name = "loyalty_points")
public class LoyaltyAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true, length = 50)
    private String userId;

    @Column(name = "total_points", nullable = false, precision = 12, scale = 4)
    private BigDecimal totalPoints = BigDecimal.ZERO;

    protected LoyaltyAccount() { /* JPA */ }

    public LoyaltyAccount(String userId) {
        this.userId = userId;
    }

    // ---------------------------------------------------------------- mutators

    /** Adds the given point amount (must be positive). */
    public void addPoints(BigDecimal points) {
        this.totalPoints = this.totalPoints.add(points);
    }

    /** Deducts the given point amount. Caller must verify sufficient balance first. */
    public void deductPoints(BigDecimal points) {
        this.totalPoints = this.totalPoints.subtract(points);
    }

    public boolean hasAtLeast(BigDecimal threshold) {
        return this.totalPoints.compareTo(threshold) >= 0;
    }

    // ---------------------------------------------------------------- getters

    public Long getId()               { return id; }
    public String getUserId()         { return userId; }
    public BigDecimal getTotalPoints(){ return totalPoints; }
}
