package bd.edu.uiu.unipay.vendor;

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
 * Records a vendor cash-out / withdrawal transaction to MFS (bKash, Nagad, Rocket)
 * or Bank Account.
 */
@Entity
@Table(name = "vendor_cashouts")
public class VendorCashout {

    @Id
    @Column(name = "cashout_id", length = 50)
    private String cashoutId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vendor_id", nullable = false)
    private User vendor;

    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private CashoutChannel channel;

    @Column(name = "account_number", nullable = false, length = 50)
    private String accountNumber;

    @Column(name = "account_holder_name", length = 100)
    private String accountHolderName;

    @Column(name = "bank_name", length = 100)
    private String bankName;

    @Column(name = "branch_name", length = 100)
    private String branchName;

    @Column(name = "transaction_id", nullable = false, length = 100)
    private String transactionId;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    protected VendorCashout() {
        // JPA
    }

    public VendorCashout(String cashoutId, User vendor, BigDecimal amount, CashoutChannel channel,
                         String accountNumber, String accountHolderName, String bankName,
                         String branchName, String transactionId) {
        this.cashoutId = cashoutId;
        this.vendor = vendor;
        this.amount = amount;
        this.channel = channel;
        this.accountNumber = accountNumber;
        this.accountHolderName = accountHolderName;
        this.bankName = bankName;
        this.branchName = branchName;
        this.transactionId = transactionId;
        this.status = "COMPLETED";
    }

    public String getCashoutId() {
        return cashoutId;
    }

    public User getVendor() {
        return vendor;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public CashoutChannel getChannel() {
        return channel;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public String getAccountHolderName() {
        return accountHolderName;
    }

    public String getBankName() {
        return bankName;
    }

    public String getBranchName() {
        return branchName;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
