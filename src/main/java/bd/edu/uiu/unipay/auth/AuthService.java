package bd.edu.uiu.unipay.auth;

import bd.edu.uiu.unipay.common.ApiException;
import bd.edu.uiu.unipay.security.AppUserDetailsService;
import bd.edu.uiu.unipay.security.JwtService;
import bd.edu.uiu.unipay.user.Role;
import bd.edu.uiu.unipay.user.User;
import bd.edu.uiu.unipay.user.UserRepository;
import bd.edu.uiu.unipay.vendor.VendorProfile;
import bd.edu.uiu.unipay.vendor.VendorProfileRepository;
import bd.edu.uiu.unipay.wallet.Wallet;
import bd.edu.uiu.unipay.wallet.WalletRepository;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ID-verified registration &amp; login. Every new user automatically receives a
 * zero-balance wallet; VENDOR registrations additionally receive a merchant
 * profile carrying their stable QR identifier.
 */
@Service
public class AuthService {

    private final UserRepository users;
    private final WalletRepository wallets;
    private final VendorProfileRepository vendorProfiles;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final AppUserDetailsService userDetailsService;
    private final JwtService jwtService;

    public AuthService(UserRepository users,
                       WalletRepository wallets,
                       VendorProfileRepository vendorProfiles,
                       PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager,
                       AppUserDetailsService userDetailsService,
                       JwtService jwtService) {
        this.users = users;
        this.wallets = wallets;
        this.vendorProfiles = vendorProfiles;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.userDetailsService = userDetailsService;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthDtos.AuthResponse register(AuthDtos.RegisterRequest request) {
        if (request.role() == Role.VENDOR
                && (request.stallName() == null || request.stallName().isBlank()
                || request.stallCategory() == null)) {
            throw ApiException.badRequest("Vendor accounts must provide stallName and stallCategory.");
        }

        String userId = request.userId().trim();
        String phone = request.phoneNumber().trim();
        if (users.existsByUserIdOrPhoneNumber(userId, phone)) {
            throw ApiException.conflict("This University ID or phone number is already registered.");
        }

        User user = users.save(new User(
                userId,
                request.fullName().trim(),
                phone,
                passwordEncoder.encode(request.password()),
                request.role()));

        wallets.save(new Wallet(user));

        if (user.getRole() == Role.VENDOR) {
            vendorProfiles.save(new VendorProfile(user, request.stallName().trim(), request.stallCategory()));
        }

        return buildResponse(user);
    }

    public AuthDtos.AuthResponse login(AuthDtos.LoginRequest request) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.userId().trim(), request.password()));
            String userId = authentication.getName();
            User user = users.findById(userId)
                    .orElseThrow(() -> ApiException.unauthorized("Invalid University ID or password."));
            return buildResponse(user);
        } catch (AuthenticationException ex) {
            throw ApiException.unauthorized("Invalid University ID or password.");
        }
    }

    private AuthDtos.AuthResponse buildResponse(User user) {
        String token = jwtService.issueToken(user.getUserId(), user.getRole().name());
        return new AuthDtos.AuthResponse(token, AuthDtos.UserDto.from(user));
    }
}
