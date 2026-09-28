package bd.edu.uiu.unipay.payment;

import bd.edu.uiu.unipay.audit.AuditService;
import bd.edu.uiu.unipay.common.ApiException;
import bd.edu.uiu.unipay.loyalty.LoyaltyService;
import bd.edu.uiu.unipay.notification.NotificationService;
import bd.edu.uiu.unipay.transaction.Transaction;
import bd.edu.uiu.unipay.transaction.TransactionRepository;
import bd.edu.uiu.unipay.transaction.TransactionType;
import bd.edu.uiu.unipay.user.Role;
import bd.edu.uiu.unipay.user.User;
import bd.edu.uiu.unipay.user.UserRepository;
import bd.edu.uiu.unipay.vendor.DynamicQrCache;
import bd.edu.uiu.unipay.vendor.VendorProfile;
import bd.edu.uiu.unipay.vendor.VendorProfileRepository;
import bd.edu.uiu.unipay.wallet.Wallet;
import bd.edu.uiu.unipay.wallet.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of the money-movement engine — wallet balance rules, deterministic
 * lock ordering and idempotency, all in isolation with Mockito.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock private UserRepository users;
    @Mock private WalletRepository wallets;
    @Mock private TransactionRepository transactions;
    @Mock private VendorProfileRepository vendorProfiles;
    @Mock private DynamicQrCache dynamicQrCache;
    @Mock private NotificationService notifications;
    @Mock private AuditService audit;
    @Mock private LoyaltyService loyaltyService;

    private PaymentService service;

    private final User alice = new User("AAA111", "Alice", "01711111111", "hash", Role.STUDENT);
    private final User bob = new User("BBB222", "Bob", "01722222222", "hash", Role.STUDENT);
    private Wallet aliceWallet;
    private Wallet bobWallet;

    @BeforeEach
    void setUp() {
        service = new PaymentService(users, wallets, transactions, vendorProfiles,
                dynamicQrCache, notifications, audit, loyaltyService);
        aliceWallet = new Wallet(alice);
        bobWallet = new Wallet(bob);
        aliceWallet.credit(new BigDecimal("100.00"));

        lenient().when(users.findById("AAA111")).thenReturn(Optional.of(alice));
        lenient().when(users.findById("BBB222")).thenReturn(Optional.of(bob));
        lenient().when(users.findByPhoneNumber("01722222222")).thenReturn(Optional.of(bob));
        lenient().when(wallets.findWalletForUpdateByUserId(anyString()))
                .thenAnswer(inv -> "AAA111".equals(inv.getArgument(0))
                        ? Optional.of(aliceWallet) : Optional.of(bobWallet));
        lenient().when(transactions.findById(anyString())).thenReturn(Optional.empty());
        lenient().when(transactions.save(any(Transaction.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private PaymentDtos.P2PTransferRequest p2p(String recipient, String amount, String nonce) {
        return new PaymentDtos.P2PTransferRequest(recipient, new BigDecimal(amount), nonce);
    }

    @Test
    void p2pMovesMoneyAtZeroFee() {
        var res = service.p2pTransfer("AAA111", p2p("BBB222", "40.50", "n1"));

        assertThat(res.amount()).isEqualByComparingTo("40.50");
        assertThat(res.payerNewBalance()).isEqualByComparingTo("59.50");
        assertThat(aliceWallet.getCurrentBalance()).isEqualByComparingTo("59.50"); // no fee deducted
        assertThat(bobWallet.getCurrentBalance()).isEqualByComparingTo("40.50");
        verify(transactions).save(any(Transaction.class));
    }

    @Test
    void p2pAcceptsPhoneNumberAsRecipient() {
        service.p2pTransfer("AAA111", p2p("01722222222", "10", "n2"));
        assertThat(bobWallet.getCurrentBalance()).isEqualByComparingTo("10.00");
    }

    @Test
    void insufficientBalanceIsRejectedWithoutSideEffects() {
        assertThatThrownBy(() -> service.p2pTransfer("AAA111", p2p("BBB222", "100.01", "n3")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Insufficient balance");

        assertThat(aliceWallet.getCurrentBalance()).isEqualByComparingTo("100.00"); // unchanged
        assertThat(bobWallet.getCurrentBalance()).isEqualByComparingTo("0.00");
        verify(transactions, never()).save(any(Transaction.class));
    }

    @Test
    void selfTransferIsRejected() {
        assertThatThrownBy(() -> service.p2pTransfer("AAA111", p2p("AAA111", "5", "n4")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("yourself");
    }

    @Test
    void unknownRecipientGives404() {
        assertThatThrownBy(() -> service.p2pTransfer("AAA111", p2p("NOBODY", "5", "n5")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("No registered UniPay user");
    }

    @Test
    void duplicateNonceReplaysExistingLedgerEntryWithoutDoubleSpending() {
        Transaction original = new Transaction("TXN-dup", alice, bob,
                new BigDecimal("10.00"), TransactionType.P2P_TRANSFER);
        when(transactions.findById("TXN-dup")).thenReturn(Optional.of(original));

        var res = service.p2pTransfer("AAA111", p2p("BBB222", "10", "dup"));

        assertThat(res.duplicate()).isTrue();
        assertThat(aliceWallet.getCurrentBalance()).isEqualByComparingTo("100.00"); // untouched
        verify(transactions, never()).save(any(Transaction.class));
    }

    @Test
    void walletsAreAlwaysLockedInAscendingUserIdOrder_deadlockPrevention() {
        // Alice (AAA111) pays Bob (BBB222) — lock must start with AAA111.
        service.p2pTransfer("AAA111", p2p("BBB222", "1", "n6"));

        InOrder order = inOrder(wallets);
        order.verify(wallets).findWalletForUpdateByUserId("AAA111");
        order.verify(wallets).findWalletForUpdateByUserId("BBB222");

        // And the reverse transfer must use the same global order.
        service.p2pTransfer("BBB222", p2p("AAA111", "1", "n7"));
        InOrder order2 = inOrder(wallets);
        order2.verify(wallets).findWalletForUpdateByUserId("AAA111");
        order2.verify(wallets).findWalletForUpdateByUserId("BBB222");
    }

    // ---------------------------------------------------------- vendor POS

    private void givenVendor() {
        User vendorUser = new User("V-001", "Canteen", "01733333333", "hash", Role.VENDOR);
        lenient().when(users.findById("V-001")).thenReturn(Optional.of(vendorUser));
        lenient().when(vendorProfiles.findById("V-001"))
                .thenReturn(Optional.of(new VendorProfile(vendorUser, "Canteen", bd.edu.uiu.unipay.vendor.StallCategory.CANTEEN)));
        lenient().when(wallets.findWalletForUpdateByUserId("V-001"))
                .thenReturn(Optional.of(new Wallet(vendorUser)));
    }

    private PaymentDtos.VendorPaymentRequest vendorPay(String payload, String amount, String nonce) {
        return new PaymentDtos.VendorPaymentRequest(payload, null, new BigDecimal(amount), nonce);
    }

    @Test
    void vendorPaymentFromScannedStaticQrIsZeroCharge() {
        givenVendor();

        var res = service.vendorPayment("AAA111",
                vendorPay("UNIPAY:VENDOR:V-001", "15.00", "v1"));

        assertThat(res.type()).isEqualTo("VENDOR_PAYMENT");
        assertThat(res.amount()).isEqualByComparingTo("15.00");
        assertThat(aliceWallet.getCurrentBalance()).isEqualByComparingTo("85.00");
    }

    @Test
    void dynamicQrCanBeRedeemedExactlyOnce() {
        givenVendor();
        when(dynamicQrCache.consume(eq("nonce-9"), eq("V-001"))).thenReturn(true, false);

        service.vendorPayment("AAA111",
                vendorPay("UNIPAY:VENDOR:V-001:NONCE:nonce-9", "20", "v2"));

        // replaying the same one-time code must fail
        assertThatThrownBy(() -> service.vendorPayment("AAA111",
                vendorPay("UNIPAY:VENDOR:V-001:NONCE:nonce-9", "20", "v3")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("expired or was already used");
    }

    @Test
    void presetAmountOnDynamicQrIsEnforced() {
        givenVendor();

        // rejected on amount mismatch BEFORE the one-time nonce is redeemed
        assertThatThrownBy(() -> service.vendorPayment("AAA111",
                vendorPay("UNIPAY:VENDOR:V-001:AMT:50:NONCE:n", "30", "v4")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("exactly");
        verify(dynamicQrCache, never()).consume(anyString(), anyString());
    }

    @Test
    void foreignQrCodeIsRejected() {
        assertThatThrownBy(() -> service.vendorPayment("AAA111",
                vendorPay("BKASH:PAY:SOMEONE", "10", "v5")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not a UniPay vendor code");
    }

    @Test
    void payingANonVendorAccountIsRejected() {
        assertThatThrownBy(() -> service.vendorPayment("AAA111",
                vendorPay("UNIPAY:VENDOR:BBB222", "10", "v6")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not a vendor");
    }
}
