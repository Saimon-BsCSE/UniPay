package bd.edu.uiu.unipay.otp;

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
 * Persisted OTP record. The {@code otpHash} column stores a BCrypt-hashed
 * 6-digit code so plaintext OTPs are never written to the database.
 *
 * <p>TTL is enforced at the application layer by comparing {@code expiresAt}
 * against {@code Instant.now()} — no database job needed.</p>
 *
 * <p>The {@code nonce} field is the client-supplied idempotency token that will
 * be forwarded to {@code MfsService.cashIn} upon successful verification,
 * ensuring replay-safe balance top-ups.</p>
 */
@Entity
@Table(name = "otp_records")
public class OtpRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 50)
    private String userId;

    /** MFS provider code: BKASH | NAGAD | ROCKET */
    @Column(nullable = false, length = 10)
    private String provider;

    @Column(name = "phone_number", nullable = false, length = 15)
    private String phoneNumber;

    /** BCrypt hash of the 6-digit OTP — never store plaintext. */
    @Column(name = "otp_hash", nullable = false, length = 255)
    private String otpHash;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    /** Client idempotency nonce forwarded verbatim to MfsService.cashIn(). */
    @Column(nullable = false, length = 100)
    private String nonce;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 3;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @CreationTimestamp
    @Column(name = "created_at")
    private Instant createdAt;

    protected OtpRecord() { /* JPA */ }

    public OtpRecord(String userId, String provider, String phoneNumber,
                     String otpHash, BigDecimal amount, String nonce, Instant expiresAt) {
        this.userId = userId;
        this.provider = provider;
        this.phoneNumber = phoneNumber;
        this.otpHash = otpHash;
        this.amount = amount;
        this.nonce = nonce;
        this.expiresAt = expiresAt;
    }

    // ---------------------------------------------------------------- mutators

    public void incrementAttempts() { this.attempts++; }

    public boolean isExpired() { return Instant.now().isAfter(expiresAt); }

    public boolean isLocked() { return attempts >= maxAttempts; }

    // ---------------------------------------------------------------- getters

    public Long getId()           { return id; }
    public String getUserId()     { return userId; }
    public String getProvider()   { return provider; }
    public String getPhoneNumber(){ return phoneNumber; }
    public String getOtpHash()    { return otpHash; }
    public BigDecimal getAmount() { return amount; }
    public String getNonce()      { return nonce; }
    public int getAttempts()      { return attempts; }
    public int getMaxAttempts()   { return maxAttempts; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
}
