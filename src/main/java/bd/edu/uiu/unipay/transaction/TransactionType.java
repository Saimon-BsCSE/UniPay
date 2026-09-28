package bd.edu.uiu.unipay.transaction;

/**
 * Ledger entry types (see Transactions.transaction_type ENUM in the proposal).
 */
public enum TransactionType {
    MFS_CASH_IN,
    VENDOR_PAYMENT,
    P2P_TRANSFER,
    SPLIT_PAY,
    VENDOR_CASHOUT,
    LOYALTY_REDEMPTION
}
