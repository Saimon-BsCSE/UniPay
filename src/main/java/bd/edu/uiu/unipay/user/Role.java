package bd.edu.uiu.unipay.user;

/**
 * System roles supported by UniPay (see Users.role ENUM in the proposal schema).
 * Used by Spring Security for Role-Based Access Control (RBAC).
 */
public enum Role {
    STUDENT,
    FACULTY,
    STAFF,
    VENDOR
}
