package bd.edu.uiu.unipay.wallet;

import bd.edu.uiu.unipay.transaction.Transaction;
import bd.edu.uiu.unipay.transaction.TransactionType;
import bd.edu.uiu.unipay.user.Role;
import bd.edu.uiu.unipay.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Direction is a money-sign claim the UI renders in red or green, so it is worth pinning down
 * per transaction type rather than trusting a single "is the viewer the sender?" test.
 *
 * <p>The three self-to-self types are the reason: cash-in top-ups and loyalty redemptions are
 * booked sender == receiver yet credit the wallet, while a vendor cash-out is booked the same way
 * but debits it. Two bugs have come out of this area — top-ups rendering as negative amounts, then
 * cash-outs flipping to positive — so each type gets its own case here.
 */
class WalletServiceDirectionTest {

    private static final User STUDENT = new User("0112330140", "Md Saimon Islam", "01712345640", "x", Role.STUDENT);
    private static final User VENDOR = new User("V-CAFE-01", "UIU Central Canteen", "01712345645", "x", Role.VENDOR);
    private static final User OTHER = new User("0112330378", "Osama Bin Mansur", "01712345641", "x", Role.STUDENT);

    private static Transaction txn(TransactionType type, User sender, User receiver) {
        return new Transaction("TXN-TEST-1", sender, receiver, new BigDecimal("50.00"), type);
    }

    private static String directionOf(TransactionType type, User sender, User receiver, String viewerId) {
        return WalletService.toDto(txn(type, sender, receiver), viewerId).direction();
    }

    @Test
    @DisplayName("cash-in top-up is money arriving, even though it is booked self-to-self")
    void cashInIsIncoming() {
        assertEquals("IN", directionOf(TransactionType.MFS_CASH_IN, STUDENT, STUDENT, STUDENT.getUserId()));
    }

    @Test
    @DisplayName("loyalty redemption is money arriving, even though it is booked self-to-self")
    void loyaltyRedemptionIsIncoming() {
        assertEquals("IN", directionOf(TransactionType.LOYALTY_REDEMPTION, STUDENT, STUDENT, STUDENT.getUserId()));
    }

    @Test
    @DisplayName("vendor cash-out is money leaving, even though it is booked vendor-to-vendor")
    void vendorCashoutIsOutgoing() {
        // Regression guard: VendorCashoutService debits the wallet and then persists the row with
        // sender == receiver == vendor, so a sender-based rule renders a withdrawal as a receipt.
        assertEquals("OUT", directionOf(TransactionType.VENDOR_CASHOUT, VENDOR, VENDOR, VENDOR.getUserId()));
    }

    @Test
    @DisplayName("a transfer the viewer sent is outgoing")
    void sentTransferIsOutgoing() {
        assertEquals("OUT", directionOf(TransactionType.P2P_TRANSFER, STUDENT, OTHER, STUDENT.getUserId()));
        assertEquals("OUT", directionOf(TransactionType.VENDOR_PAYMENT, STUDENT, VENDOR, STUDENT.getUserId()));
        assertEquals("OUT", directionOf(TransactionType.SPLIT_PAY, STUDENT, OTHER, STUDENT.getUserId()));
    }

    @Test
    @DisplayName("a transfer the viewer received is incoming")
    void receivedTransferIsIncoming() {
        assertEquals("IN", directionOf(TransactionType.P2P_TRANSFER, OTHER, STUDENT, STUDENT.getUserId()));
        assertEquals("IN", directionOf(TransactionType.VENDOR_PAYMENT, OTHER, VENDOR, VENDOR.getUserId()));
        assertEquals("IN", directionOf(TransactionType.SPLIT_PAY, OTHER, STUDENT, STUDENT.getUserId()));
    }

    @Test
    @DisplayName("the counterparty is the other side, not the viewer")
    void counterpartyIsTheOtherParty() {
        var sent = WalletService.toDto(txn(TransactionType.VENDOR_PAYMENT, STUDENT, VENDOR), STUDENT.getUserId());
        assertEquals(VENDOR.getUserId(), sent.counterpartyId());

        var received = WalletService.toDto(txn(TransactionType.VENDOR_PAYMENT, OTHER, VENDOR), VENDOR.getUserId());
        assertEquals(OTHER.getUserId(), received.counterpartyId());
    }

    @Test
    @DisplayName("every transaction type resolves to a valid direction")
    void allTypesResolve() {
        for (TransactionType type : TransactionType.values()) {
            String asSender = directionOf(type, STUDENT, OTHER, STUDENT.getUserId());
            String asReceiver = directionOf(type, STUDENT, OTHER, OTHER.getUserId());
            org.junit.jupiter.api.Assertions.assertTrue(
                    "IN".equals(asSender) || "OUT".equals(asSender),
                    type + " resolved to a non-direction: " + asSender);
            org.junit.jupiter.api.Assertions.assertTrue(
                    "IN".equals(asReceiver) || "OUT".equals(asReceiver),
                    type + " resolved to a non-direction: " + asReceiver);
        }
    }

    @Test
    @DisplayName("a real transfer is outgoing for one party and incoming for the other")
    void transfersAreSymmetric() {
        for (TransactionType type : new TransactionType[]{
                TransactionType.VENDOR_PAYMENT,
                TransactionType.P2P_TRANSFER,
                TransactionType.SPLIT_PAY}) {
            assertEquals("OUT", directionOf(type, STUDENT, OTHER, STUDENT.getUserId()),
                    type + " should be outgoing for the sender");
            assertEquals("IN", directionOf(type, STUDENT, OTHER, OTHER.getUserId()),
                    type + " should be incoming for the receiver");
        }
    }

    @Test
    @DisplayName("self-to-self types keep one direction no matter who is viewing")
    void selfBookedTypesAreNotViewRelative() {
        // These never reach a second party in practice, so their direction must not flip
        // with the viewer id.
        for (TransactionType type : new TransactionType[]{
                TransactionType.MFS_CASH_IN,
                TransactionType.LOYALTY_REDEMPTION,
                TransactionType.VENDOR_CASHOUT}) {
            String a = directionOf(type, STUDENT, STUDENT, STUDENT.getUserId());
            String b = directionOf(type, STUDENT, STUDENT, OTHER.getUserId());
            assertEquals(a, b, type + " direction should not depend on the viewer");
        }
    }
}
