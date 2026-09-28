package bd.edu.uiu.unipay.vendor;

import bd.edu.uiu.unipay.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

/**
 * Maps the {@code Vendor_Profiles} table — merchant metadata plus the stable
 * QR payload identifier that identifies the vendor in every static QR code.
 */
@Entity
@Table(name = "Vendor_Profiles")
public class VendorProfile {

    @Id
    @Column(name = "vendor_id", length = 50)
    private String vendorId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "vendor_id")
    private User user;

    @Column(name = "stall_name", nullable = false, length = 100)
    private String stallName;

    @Enumerated(EnumType.STRING)
    @Column(name = "stall_category", nullable = false, length = 20)
    private StallCategory stallCategory;

    /** Canonical payload embedded in the vendor's static QR code: UNIPAY:VENDOR:&lt;vendorId&gt; */
    @Column(name = "qr_code_identifier", nullable = false, unique = true, length = 255)
    private String qrCodeIdentifier;

    protected VendorProfile() {
        // JPA
    }

    public VendorProfile(User user, String stallName, StallCategory stallCategory) {
        this.user = user;
        this.stallName = stallName;
        this.stallCategory = stallCategory;
        this.qrCodeIdentifier = "UNIPAY:VENDOR:" + user.getUserId();
        // vendorId stays null — @MapsId derives it from the associated User on
        // persist(), so Spring Data JPA routes save() through persist(), not merge().
    }

    public String getVendorId() {
        return vendorId != null ? vendorId : (user != null ? user.getUserId() : null);
    }

    public User getUser() {
        return user;
    }

    public String getStallName() {
        return stallName;
    }

    public void setStallName(String stallName) {
        this.stallName = stallName;
    }

    public StallCategory getStallCategory() {
        return stallCategory;
    }

    public void setStallCategory(StallCategory stallCategory) {
        this.stallCategory = stallCategory;
    }

    public String getQrCodeIdentifier() {
        return qrCodeIdentifier;
    }
}
