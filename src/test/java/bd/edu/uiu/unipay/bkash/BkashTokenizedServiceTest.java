package bd.edu.uiu.unipay.bkash;

import bd.edu.uiu.unipay.audit.AuditService;
import bd.edu.uiu.unipay.bkash.BkashDTOs.CheckoutInitResponse;
import bd.edu.uiu.unipay.bkash.BkashDTOs.CreatePaymentResponse;
import bd.edu.uiu.unipay.bkash.BkashDTOs.ExecutePaymentResponse;
import bd.edu.uiu.unipay.bkash.BkashDTOs.GrantTokenResponse;
import bd.edu.uiu.unipay.bkash.BkashPendingPayment.Status;
import bd.edu.uiu.unipay.common.ApiException;
import bd.edu.uiu.unipay.loyalty.LoyaltyService;
import bd.edu.uiu.unipay.notification.NotificationService;
import bd.edu.uiu.unipay.transaction.Transaction;
import bd.edu.uiu.unipay.transaction.TransactionRepository;
import bd.edu.uiu.unipay.user.Role;
import bd.edu.uiu.unipay.user.User;
import bd.edu.uiu.unipay.user.UserRepository;
import bd.edu.uiu.unipay.wallet.Wallet;
import bd.edu.uiu.unipay.wallet.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests for {@link BkashTokenizedService} using {@link MockRestServiceServer}
 * (Spring's built-in HTTP mock) so no real bKash network calls are made.
 *
 * <h3>Coverage</h3>
 * <ul>
 *   <li>Grant token caching (token reused on subsequent calls)</li>
 *   <li>{@code initiateCheckout} happy path — pending row persisted</li>
 *   <li>{@code initiateCheckout} amount validation guards</li>
 *   <li>{@code handleCallback} success path — wallet credited, Transaction saved</li>
 *   <li>{@code handleCallback} cancel/failure path — no wallet credit</li>
 *   <li>{@code handleCallback} duplicate callback — idempotent</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class BkashTokenizedServiceTest {

    // ── Mock dependencies ────────────────────────────────────────────────────
    @Mock UserRepository                    userRepository;
    @Mock WalletRepository                  walletRepository;
    @Mock TransactionRepository             transactionRepository;
    @Mock BkashPendingPaymentRepository     pendingPaymentRepository;
    @Mock NotificationService               notificationService;
    @Mock AuditService                      auditService;
    // NOTE: LoyaltyService removed — bKash cash-in no longer awards loyalty coins

    // Real RestClient backed by MockRestServiceServer
    private MockRestServiceServer mockServer;
    private BkashTokenizedService service;

    // ── Test data ────────────────────────────────────────────────────────────
    private static final String BASE_URL    = "https://tokenized.sandbox.bka.sh/v1.2.0-beta";
    private static final String APP_KEY     = "test_app_key";
    private static final String APP_SECRET  = "test_app_secret";
    private static final String USERNAME    = "test_username";
    private static final String PASSWORD    = "test_password";
    private static final String CALLBACK    = "http://localhost:8080/api/bkash/callback";
    private static final String PAYMENT_ID  = "TrxPaymentID123";
    private static final String TRX_ID      = "ABC123XYZ";
    private static final String BKASH_URL   = "https://sandbox.bka.sh/pay/TrxPaymentID123";

    private User   testUser;
    private User   gatewayUser;
    private Wallet testWallet;

    @BeforeEach
    void setUp() {
        BkashProperties props = new BkashProperties();
        props.setBaseUrl(BASE_URL);
        props.setAppKey(APP_KEY);
        props.setAppSecret(APP_SECRET);
        props.setUsername(USERNAME);
        props.setPassword(PASSWORD);
        props.setCallbackUrl(CALLBACK);

        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();

        service = new BkashTokenizedService(
                props, builder,
                userRepository, walletRepository, transactionRepository,
                pendingPaymentRepository, notificationService, auditService);

        testUser    = new User("0112330140", "Test User", "01712345640", "hash", Role.STUDENT);
        gatewayUser = new User(BkashTokenizedService.BKASH_GATEWAY_USER_ID,
                "bKash Gateway", "00000000000", "hash", Role.VENDOR);
        testWallet  = new Wallet(testUser);
        testWallet.credit(BigDecimal.ZERO); // initialise to zero
    }

    // ══════════════════════════════ Grant Token ══════════════════════════════

    @Nested
    @DisplayName("Grant Token")
    class GrantTokenTests {

        @Test
        @DisplayName("fetches a new token when cache is empty")
        void fetchesNewToken() {
            stubGrantToken("validToken123", 3600L);

            String token = service.getGrantToken();

            assertThat(token).isEqualTo("validToken123");
            mockServer.verify();
        }

        @Test
        @DisplayName("returns cached token without a second HTTP call")
        void returnsCachedToken() {
            stubGrantToken("cachedToken", 3600L);

            String first  = service.getGrantToken();
            String second = service.getGrantToken();  // should NOT hit the server again

            assertThat(first).isEqualTo(second).isEqualTo("cachedToken");
            mockServer.verify();   // only one expectation was registered — passes if called once
        }
    }

    // ═══════════════════════════ initiateCheckout ════════════════════════════

    @Nested
    @DisplayName("initiateCheckout")
    class InitiateCheckoutTests {

        @Test
        @DisplayName("happy path — pending row saved, bkashURL returned")
        void happyPath() {
            stubGrantToken("tok", 3600L);
            stubCreatePayment();

            when(userRepository.findById("0112330140")).thenReturn(Optional.of(testUser));
            when(pendingPaymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            CheckoutInitResponse resp = service.initiateCheckout("0112330140", new BigDecimal("150.00"), "uuid-nonce-1");

            assertThat(resp.paymentID()).isEqualTo(PAYMENT_ID);
            assertThat(resp.bkashURL()).isEqualTo(BKASH_URL);
            assertThat(resp.amount()).isEqualTo("150.00");

            ArgumentCaptor<BkashPendingPayment> captor = ArgumentCaptor.forClass(BkashPendingPayment.class);
            verify(pendingPaymentRepository).save(captor.capture());
            BkashPendingPayment saved = captor.getValue();
            assertThat(saved.getPaymentId()).isEqualTo(PAYMENT_ID);
            assertThat(saved.getStatus()).isEqualTo(Status.PENDING);
            assertThat(saved.getNonce()).isEqualTo("uuid-nonce-1");

            mockServer.verify();
        }

        @Test
        @DisplayName("rejects amount below ৳20")
        void rejectsAmountBelowMin() {
            when(userRepository.findById(anyString())).thenReturn(Optional.of(testUser));

            assertThatThrownBy(() ->
                    service.initiateCheckout("0112330140", new BigDecimal("10.00"), "n"))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("৳20");
        }

        @Test
        @DisplayName("rejects amount above ৳50,000")
        void rejectsAmountAboveMax() {
            when(userRepository.findById(anyString())).thenReturn(Optional.of(testUser));

            assertThatThrownBy(() ->
                    service.initiateCheckout("0112330140", new BigDecimal("60000"), "n"))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("৳50,000");
        }
    }

    // ═══════════════════════════ handleCallback ══════════════════════════════

    @Nested
    @DisplayName("handleCallback")
    class HandleCallbackTests {

        @Test
        @DisplayName("success path — wallet credited, Transaction saved, status COMPLETED")
        void successPath() {
            stubGrantToken("tok", 3600L);
            stubExecutePayment();

            BkashPendingPayment pending = new BkashPendingPayment(
                    PAYMENT_ID, testUser, new BigDecimal("200.00"), "nonce-x");

            when(pendingPaymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(pending));
            when(walletRepository.findWalletForUpdateByUserId("0112330140")).thenReturn(Optional.of(testWallet));
            when(userRepository.findById(BkashTokenizedService.BKASH_GATEWAY_USER_ID)).thenReturn(Optional.of(gatewayUser));
            when(transactionRepository.existsById("BKASH-" + PAYMENT_ID)).thenReturn(false);
            when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            String redirect = service.handleCallback(PAYMENT_ID, "success");

            assertThat(redirect).contains("/payment-success").contains(TRX_ID);
            assertThat(pending.getStatus()).isEqualTo(Status.COMPLETED);
            assertThat(testWallet.getCurrentBalance()).isEqualByComparingTo("200.00");
            verify(transactionRepository).save(any(Transaction.class));
            // Loyalty coins NOT awarded on bKash cash-in — only on purchases
            mockServer.verify();
        }

        @Test
        @DisplayName("cancel status — wallet NOT credited, status FAILED")
        void cancelPath() {
            BkashPendingPayment pending = new BkashPendingPayment(
                    PAYMENT_ID, testUser, new BigDecimal("200.00"), "nonce-y");

            when(pendingPaymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(pending));

            String redirect = service.handleCallback(PAYMENT_ID, "cancel");

            assertThat(redirect).contains("/payment-failed");
            assertThat(pending.getStatus()).isEqualTo(Status.FAILED);
            verify(walletRepository, never()).findWalletForUpdateByUserId(any());
            verify(transactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("duplicate callback — idempotent, redirect based on stored status")
        void duplicateCallbackIdempotent() {
            BkashPendingPayment completed = new BkashPendingPayment(
                    PAYMENT_ID, testUser, new BigDecimal("200.00"), "nonce-z");
            completed.markCompleted();

            when(pendingPaymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(completed));

            String redirect = service.handleCallback(PAYMENT_ID, "success");

            assertThat(redirect).contains("/payment-success");
            verify(walletRepository, never()).findWalletForUpdateByUserId(any());
            verify(transactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("unknown paymentID — throws 404 ApiException")
        void unknownPaymentId() {
            when(pendingPaymentRepository.findById("UNKNOWN")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.handleCallback("UNKNOWN", "success"))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("Unknown bKash paymentID");
        }
    }

    // ── Stubs ─────────────────────────────────────────────────────────────────

    private void stubGrantToken(String token, long expiresIn) {
        String grantJson = """
                {"statusCode":"0000","statusMessage":"Successful","id_token":"%s",
                 "token_type":"Bearer","expires_in":%d,"refresh_token":"rt"}
                """.formatted(token, expiresIn);

        mockServer.expect(requestTo(BASE_URL + "/tokenized/checkout/token/grant"))
                .andExpect(header("username", USERNAME))
                .andExpect(header("password", PASSWORD))
                .andRespond(withSuccess(grantJson, MediaType.APPLICATION_JSON));
    }

    private void stubCreatePayment() {
        String createJson = """
                {"statusCode":"0000","statusMessage":"Successful","paymentID":"%s",
                 "bkashURL":"%s","callbackURL":"%s","amount":"150.00",
                 "intent":"sale","currency":"BDT","paymentCreateTime":"2026-01-01T00:00:00Z",
                 "transactionStatus":"Initiated","merchantInvoiceNumber":"UNIPAY-INV001"}
                """.formatted(PAYMENT_ID, BKASH_URL, CALLBACK);

        mockServer.expect(requestTo(BASE_URL + "/tokenized/checkout/create"))
                .andExpect(header("X-APP-Key", APP_KEY))
                .andRespond(withSuccess(createJson, MediaType.APPLICATION_JSON));
    }

    private void stubExecutePayment() {
        String executeJson = """
                {"statusCode":"0000","statusMessage":"Successful","paymentID":"%s",
                 "payerReference":"01712345640","customerMsisdn":"01712345640",
                 "trxID":"%s","amount":"200.00","transactionStatus":"Completed",
                 "paymentExecuteTime":"2026-01-01T00:01:00Z","currency":"BDT",
                 "intent":"sale","merchantInvoiceNumber":"UNIPAY-INV001"}
                """.formatted(PAYMENT_ID, TRX_ID);

        mockServer.expect(requestTo(BASE_URL + "/tokenized/checkout/execute"))
                .andExpect(header("X-APP-Key", APP_KEY))
                .andRespond(withSuccess(executeJson, MediaType.APPLICATION_JSON));
    }
}
