package bd.edu.uiu.unipay.splitpay;

/**
 * Status lifecycle of an individual SplitPay money request.
 */
public enum SplitRequestStatus {
    PENDING,
    ACCEPTED,
    DECLINED,
    CANCELLED
}
