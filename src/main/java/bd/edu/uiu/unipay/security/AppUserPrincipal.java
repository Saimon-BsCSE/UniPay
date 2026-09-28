package bd.edu.uiu.unipay.security;

import bd.edu.uiu.unipay.user.Role;
import bd.edu.uiu.unipay.user.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.security.Principal;
import java.util.Collection;
import java.util.List;

/**
 * Adapter that exposes a UniPay {@link User} to Spring Security as a principal.
 * Authorities follow the {@code ROLE_<role>} convention so endpoints can use
 * {@code hasRole('VENDOR')} etc. for RBAC. Implementing {@link Principal} also
 * lets the same object act as the STOMP user identity on the WebSocket channel.
 */
public class AppUserPrincipal implements UserDetails, Principal {

    private final User user;

    public AppUserPrincipal(User user) {
        this.user = user;
    }

    public String getId() {
        return user.getUserId();
    }

    public String getFullName() {
        return user.getFullName();
    }

    public Role getRole() {
        return user.getRole();
    }

    public String getPhoneNumber() {
        return user.getPhoneNumber();
    }

    public String getAvatarUrl() {
        return user.getAvatarUrl();
    }

    public User getUser() {
        return user;
    }

    @Override
    public String getName() {
        return user.getUserId();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
    }

    @Override
    public String getPassword() {
        return user.getPasswordHash();
    }

    @Override
    public String getUsername() {
        return user.getUserId();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
