package bd.edu.uiu.unipay.auth;

import bd.edu.uiu.unipay.common.ApiException;
import bd.edu.uiu.unipay.security.AppUserDetailsService;
import bd.edu.uiu.unipay.security.JwtService;
import bd.edu.uiu.unipay.user.Role;
import bd.edu.uiu.unipay.user.User;
import bd.edu.uiu.unipay.user.UserRepository;
import bd.edu.uiu.unipay.vendor.StallCategory;
import bd.edu.uiu.unipay.vendor.VendorProfile;
import bd.edu.uiu.unipay.vendor.VendorProfileRepository;
import bd.edu.uiu.unipay.wallet.Wallet;
import bd.edu.uiu.unipay.wallet.WalletRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Registration &amp; login business rules (unit level).
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository users;
    @Mock private WalletRepository wallets;
    @Mock private VendorProfileRepository vendorProfiles;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuthenticationManager authenticationManager;
    @Mock private AppUserDetailsService userDetailsService;
    @Mock private JwtService jwtService;

    private AuthService service() {
        return new AuthService(users, wallets, vendorProfiles, passwordEncoder,
                authenticationManager, userDetailsService, jwtService);
    }

    private AuthDtos.RegisterRequest student(String id, String phone) {
        return new AuthDtos.RegisterRequest(id, "Test User", phone, "secret1", Role.STUDENT, null, null);
    }

    @Test
    void registrationCreatesUserWithBcryptHashAndZeroBalanceWallet() {
        when(users.existsByUserIdOrPhoneNumber("0112330999", "01799999999")).thenReturn(false);
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(passwordEncoder.encode("secret1")).thenReturn("bcrypt-hash");
        when(jwtService.issueToken("0112330999", "STUDENT")).thenReturn("jwt-token");

        var res = service().register(student("0112330999", "01799999999"));

        assertThat(res.token()).isEqualTo("jwt-token");
        assertThat(res.user().role()).isEqualTo("STUDENT");

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(users).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getPasswordHash()).isEqualTo("bcrypt-hash");

        ArgumentCaptor<Wallet> walletCaptor = ArgumentCaptor.forClass(Wallet.class);
        verify(wallets).save(walletCaptor.capture());
        assertThat(walletCaptor.getValue().getCurrentBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    void vendorRegistrationAlsoCreatesMerchantProfileWithQrIdentifier() {
        when(users.existsByUserIdOrPhoneNumber("V-NEW-01", "01799999998")).thenReturn(false);
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(passwordEncoder.encode(any())).thenReturn("bcrypt-hash");
        when(jwtService.issueToken(any(), any())).thenReturn("jwt");

        service().register(new AuthDtos.RegisterRequest(
                "V-NEW-01", "Juice Bar", "01799999998", "secret1",
                Role.VENDOR, "UIU Juice Bar", StallCategory.FOOD_STALL));

        ArgumentCaptor<VendorProfile> captor = ArgumentCaptor.forClass(VendorProfile.class);
        verify(vendorProfiles).save(captor.capture());
        assertThat(captor.getValue().getQrCodeIdentifier()).isEqualTo("UNIPAY:VENDOR:V-NEW-01");
    }

    @Test
    void vendorRegistrationWithoutStallDetailsIsRejected() {
        var request = new AuthDtos.RegisterRequest(
                "V-NEW-02", "Ghost Stall", "01799999997", "secret1",
                Role.VENDOR, null, null);

        assertThatThrownBy(() -> service().register(request))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("stallName");
    }

    @Test
    void duplicateIdOrPhoneIsRejectedWith409() {
        when(users.existsByUserIdOrPhoneNumber("0112330140", "01712345640")).thenReturn(true);

        assertThatThrownBy(() -> service().register(student("0112330140", "01712345640")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already registered");
    }

    @Test
    void loginWithWrongPasswordFailsWith401AndNoTokenLeak() {
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("bad"));

        assertThatThrownBy(() -> service().login(new AuthDtos.LoginRequest("0112330140", "wrong")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Invalid University ID or password");
    }

    @Test
    void loginSuccessReturnsJwt() {
        Authentication auth = UsernamePasswordAuthenticationToken
                .authenticated("0112330140", null, java.util.List.of());
        when(authenticationManager.authenticate(any())).thenReturn(auth);
        when(users.findById("0112330140")).thenReturn(Optional.of(
                new User("0112330140", "Saimon", "01712345640", "hash", Role.STUDENT)));
        when(jwtService.issueToken("0112330140", "STUDENT")).thenReturn("signed-jwt");

        var res = service().login(new AuthDtos.LoginRequest("0112330140", "demo1234"));

        assertThat(res.token()).isEqualTo("signed-jwt");
        assertThat(res.user().userId()).isEqualTo("0112330140");
    }
}
