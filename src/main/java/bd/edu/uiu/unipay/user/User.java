package bd.edu.uiu.unipay.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Maps the {@code Users} table of the approved proposal schema.
 * The University ID (Student / Employee / Vendor ID) is the primary key.
 */
@Entity
@Table(name = "Users")
public class User {

    @Id
    @Column(name = "user_id", length = 50)
    private String userId;

    @Column(name = "full_name", nullable = false, length = 100)
    private String fullName;

    @Column(name = "phone_number", nullable = false, unique = true, length = 15)
    private String phoneNumber;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role;

    @Column(name = "avatar_url", columnDefinition = "CLOB")
    private String avatarUrl;

    @CreationTimestamp
    @Column(name = "created_at")
    private Instant createdAt;

    protected User() {
        // JPA
    }

    public User(String userId, String fullName, String phoneNumber, String passwordHash, Role role) {
        this.userId = userId;
        this.fullName = fullName;
        this.phoneNumber = phoneNumber;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    public String getUserId() {
        return userId;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return "User[" + userId + ", " + role + "]";
    }
}
