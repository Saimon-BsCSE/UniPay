package bd.edu.uiu.unipay.splitpay;

import bd.edu.uiu.unipay.user.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a bill split among campus peers (e.g. at UIU Canteen or restaurant).
 */
@Entity
@Table(name = "split_bills")
public class SplitBill {

    @Id
    @Column(name = "bill_id", length = 50)
    private String billId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "creator_id", nullable = false)
    private User creator;

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "split_type", nullable = false, length = 20)
    private SplitType splitType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SplitBillStatus status;

    @Column(name = "note", length = 255)
    private String note;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "bill", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<SplitRequest> requests = new ArrayList<>();

    protected SplitBill() {
        // JPA
    }

    public SplitBill(String billId, User creator, String title, BigDecimal totalAmount,
                     SplitType splitType, String note) {
        this.billId = billId;
        this.creator = creator;
        this.title = title;
        this.totalAmount = totalAmount;
        this.splitType = splitType;
        this.status = SplitBillStatus.ACTIVE;
        this.note = note;
    }

    public String getBillId() {
        return billId;
    }

    public User getCreator() {
        return creator;
    }

    public String getTitle() {
        return title;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public SplitType getSplitType() {
        return splitType;
    }

    public SplitBillStatus getStatus() {
        return status;
    }

    public void setStatus(SplitBillStatus status) {
        this.status = status;
    }

    public String getNote() {
        return note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<SplitRequest> getRequests() {
        return requests;
    }

    public void addRequest(SplitRequest request) {
        requests.add(request);
        request.setBill(this);
    }
}
