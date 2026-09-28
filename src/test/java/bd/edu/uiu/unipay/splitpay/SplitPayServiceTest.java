package bd.edu.uiu.unipay.splitpay;

import bd.edu.uiu.unipay.audit.AuditService;
import bd.edu.uiu.unipay.common.ApiException;
import bd.edu.uiu.unipay.notification.NotificationService;
import bd.edu.uiu.unipay.transaction.Transaction;
import bd.edu.uiu.unipay.transaction.TransactionRepository;
import bd.edu.uiu.unipay.transaction.TransactionType;
import bd.edu.uiu.unipay.user.Role;
import bd.edu.uiu.unipay.user.User;
import bd.edu.uiu.unipay.user.UserRepository;
import bd.edu.uiu.unipay.wallet.Wallet;
import bd.edu.uiu.unipay.wallet.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SplitPayServiceTest {

    @Mock
    private SplitBillRepository billRepository;
    @Mock
    private SplitRequestRepository requestRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private WalletRepository walletRepository;
    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private NotificationService notificationService;
    @Mock
    private AuditService auditService;

    private SplitPayService service;

    private User saimon;
    private User osama;
    private User sakib;

    private Wallet saimonWallet;
    private Wallet osamaWallet;

    @BeforeEach
    void setUp() {
        service = new SplitPayService(
                billRepository, requestRepository, userRepository,
                walletRepository, transactionRepository, notificationService, auditService
        );

        saimon = new User("0112330140", "Md Saimon Islam", "01712345640", "hash", Role.STUDENT);
        osama = new User("0112330378", "Osama Bin Mansur", "01712345641", "hash", Role.STUDENT);
        sakib = new User("0112330586", "Md Sadman Sakib", "01712345642", "hash", Role.STUDENT);

        saimonWallet = new Wallet(saimon);
        saimonWallet.credit(new BigDecimal("1500.00"));

        osamaWallet = new Wallet(osama);
        osamaWallet.credit(new BigDecimal("1200.00"));
    }

    @Test
    @DisplayName("Even split divides total equally among creator and participants")
    void createBill_evenSplit_success() {
        when(userRepository.findById("0112330140")).thenReturn(Optional.of(saimon));
        when(userRepository.findById("0112330378")).thenReturn(Optional.of(osama));
        when(userRepository.findById("0112330586")).thenReturn(Optional.of(sakib));
        when(billRepository.save(any(SplitBill.class))).thenAnswer(i -> i.getArgument(0));

        SplitDtos.CreateBillRequest req = new SplitDtos.CreateBillRequest(
                "UIU Central Canteen Lunch",
                new BigDecimal("1200.00"),
                SplitType.EVEN,
                "Celebration lunch",
                List.of(
                        new SplitDtos.ParticipantItem("0112330378", null),
                        new SplitDtos.ParticipantItem("0112330586", null)
                )
        );

        SplitDtos.BillResponse res = service.createBill("0112330140", req);

        assertThat(res.title()).isEqualTo("UIU Central Canteen Lunch");
        assertThat(res.totalAmount()).isEqualByComparingTo("1200.00");
        assertThat(res.participants()).hasSize(2);
        // 1200 / 3 = 400 each
        assertThat(res.participants().get(0).amount()).isEqualByComparingTo("400.00");
        assertThat(res.participants().get(1).amount()).isEqualByComparingTo("400.00");
        assertThat(res.creatorShare()).isEqualByComparingTo("400.00");
    }

    @Test
    @DisplayName("Custom split assigns specific amounts and calculates creator share")
    void createBill_customSplit_success() {
        when(userRepository.findById("0112330140")).thenReturn(Optional.of(saimon));
        when(userRepository.findById("0112330378")).thenReturn(Optional.of(osama));
        when(userRepository.findById("0112330586")).thenReturn(Optional.of(sakib));
        when(billRepository.save(any(SplitBill.class))).thenAnswer(i -> i.getArgument(0));

        SplitDtos.CreateBillRequest req = new SplitDtos.CreateBillRequest(
                "Khan's Kitchen Treat",
                new BigDecimal("1200.00"),
                SplitType.CUSTOM,
                "Itemized lunch",
                List.of(
                        new SplitDtos.ParticipantItem("0112330378", new BigDecimal("500.00")),
                        new SplitDtos.ParticipantItem("0112330586", new BigDecimal("400.00"))
                )
        );

        SplitDtos.BillResponse res = service.createBill("0112330140", req);

        assertThat(res.totalAmount()).isEqualByComparingTo("1200.00");
        assertThat(res.participants().get(0).amount()).isEqualByComparingTo("500.00");
        assertThat(res.participants().get(1).amount()).isEqualByComparingTo("400.00");
        // 1200 - 500 - 400 = 300 creator share
        assertThat(res.creatorShare()).isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("Custom split throws badRequest when participant amounts exceed total")
    void createBill_customSplit_exceedsTotal_throwsBadRequest() {
        when(userRepository.findById("0112330140")).thenReturn(Optional.of(saimon));
        when(userRepository.findById("0112330378")).thenReturn(Optional.of(osama));

        SplitDtos.CreateBillRequest req = new SplitDtos.CreateBillRequest(
                "Snack bill",
                new BigDecimal("500.00"),
                SplitType.CUSTOM,
                null,
                List.of(new SplitDtos.ParticipantItem("0112330378", new BigDecimal("600.00")))
        );

        assertThatThrownBy(() -> service.createBill("0112330140", req))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("exceed the bill amount");
    }

    @Test
    @DisplayName("Adding oneself as participant throws badRequest")
    void createBill_selfAsParticipant_throwsBadRequest() {
        when(userRepository.findById("0112330140")).thenReturn(Optional.of(saimon));

        SplitDtos.CreateBillRequest req = new SplitDtos.CreateBillRequest(
                "Lunch",
                new BigDecimal("500.00"),
                SplitType.EVEN,
                null,
                List.of(new SplitDtos.ParticipantItem("0112330140", null))
        );

        assertThatThrownBy(() -> service.createBill("0112330140", req))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("cannot add yourself");
    }

    @Test
    @DisplayName("Accepting split request debits participant and credits creator atomically")
    void acceptRequest_sufficientBalance_transfersMoney() {
        SplitBill bill = new SplitBill("SPLIT-001", saimon, "Olympia Cafe", new BigDecimal("600.00"),
                SplitType.EVEN, "Evening snacks");
        SplitRequest req = new SplitRequest("REQ-001", bill, osama, new BigDecimal("300.00"));

        when(requestRepository.findByIdWithDetails("REQ-001")).thenReturn(Optional.of(req));
        // Ascending lock order: 0112330140 (saimon) < 0112330378 (osama)
        when(walletRepository.findWalletForUpdateByUserId("0112330140")).thenReturn(Optional.of(saimonWallet));
        when(walletRepository.findWalletForUpdateByUserId("0112330378")).thenReturn(Optional.of(osamaWallet));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));
        when(requestRepository.findByBill_BillId("SPLIT-001")).thenReturn(List.of(req));

        SplitDtos.AcceptResponse res = service.acceptRequest("0112330378", "REQ-001");

        assertThat(res.amount()).isEqualByComparingTo("300.00");
        assertThat(res.payerNewBalance()).isEqualByComparingTo("900.00"); // 1200 - 300
        assertThat(saimonWallet.getCurrentBalance()).isEqualByComparingTo("1800.00"); // 1500 + 300
        assertThat(req.getStatus()).isEqualTo(SplitRequestStatus.ACCEPTED);
        assertThat(bill.getStatus()).isEqualTo(SplitBillStatus.SETTLED);
        verify(requestRepository).save(req);
    }

    @Test
    @DisplayName("Accepting request with insufficient balance throws conflict")
    void acceptRequest_insufficientBalance_throwsConflict() {
        SplitBill bill = new SplitBill("SPLIT-001", saimon, "Grand Lunch", new BigDecimal("3000.00"),
                SplitType.EVEN, null);
        SplitRequest req = new SplitRequest("REQ-001", bill, osama, new BigDecimal("1500.00"));

        when(requestRepository.findByIdWithDetails("REQ-001")).thenReturn(Optional.of(req));
        when(walletRepository.findWalletForUpdateByUserId("0112330140")).thenReturn(Optional.of(saimonWallet));
        when(walletRepository.findWalletForUpdateByUserId("0112330378")).thenReturn(Optional.of(osamaWallet));

        // osama has 1200.00, request is 1500.00
        assertThatThrownBy(() -> service.acceptRequest("0112330378", "REQ-001"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Insufficient balance");
    }

    @Test
    @DisplayName("Declining split request sets status to DECLINED")
    void declineRequest_success() {
        SplitBill bill = new SplitBill("SPLIT-001", saimon, "Juice Bar", new BigDecimal("200.00"),
                SplitType.EVEN, null);
        SplitRequest req = new SplitRequest("REQ-001", bill, osama, new BigDecimal("100.00"));

        when(requestRepository.findByIdWithDetails("REQ-001")).thenReturn(Optional.of(req));

        service.declineRequest("0112330378", "REQ-001");

        assertThat(req.getStatus()).isEqualTo(SplitRequestStatus.DECLINED);
        verify(requestRepository).save(req);
    }
}
