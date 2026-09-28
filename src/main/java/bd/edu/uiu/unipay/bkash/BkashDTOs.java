package bd.edu.uiu.unipay.bkash;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request / response record classes for the bKash Tokenized Checkout API
 * v1.2.0-beta. All fields use {@code @JsonProperty} to align with the exact
 * camelCase / snake_case contract documented by bKash.
 */
public final class BkashDTOs {

    private BkashDTOs() {}

    // ─────────────────────────── Grant Token ────────────────────────────────

    public record GrantTokenRequest(
            @JsonProperty("app_key")    String appKey,
            @JsonProperty("app_secret") String appSecret
    ) {}

    public record GrantTokenResponse(
            @JsonProperty("statusCode")     String statusCode,
            @JsonProperty("statusMessage")  String statusMessage,
            @JsonProperty("id_token")       String idToken,
            @JsonProperty("token_type")     String tokenType,
            @JsonProperty("expires_in")     Long   expiresIn,
            @JsonProperty("refresh_token")  String refreshToken
    ) {}

    // ─────────────────────────── Create Payment ─────────────────────────────

    public record CreatePaymentRequest(
            @JsonProperty("mode")                  String mode,               // "0011" Tokenized Checkout
            @JsonProperty("payerReference")        String payerReference,     // customer phone / user-id
            @JsonProperty("callbackURL")           String callbackURL,
            @JsonProperty("amount")                String amount,             // e.g. "150.00"
            @JsonProperty("currency")              String currency,           // "BDT"
            @JsonProperty("intent")                String intent,             // "sale"
            @JsonProperty("merchantInvoiceNumber") String merchantInvoiceNumber
    ) {}

    public record CreatePaymentResponse(
            @JsonProperty("statusCode")            String statusCode,
            @JsonProperty("statusMessage")         String statusMessage,
            @JsonProperty("paymentID")             String paymentID,
            @JsonProperty("bkashURL")              String bkashURL,
            @JsonProperty("callbackURL")           String callbackURL,
            @JsonProperty("amount")                String amount,
            @JsonProperty("intent")                String intent,
            @JsonProperty("currency")              String currency,
            @JsonProperty("paymentCreateTime")     String paymentCreateTime,
            @JsonProperty("transactionStatus")     String transactionStatus,
            @JsonProperty("merchantInvoiceNumber") String merchantInvoiceNumber
    ) {}

    // ─────────────────────────── Execute Payment ────────────────────────────

    public record ExecutePaymentRequest(
            @JsonProperty("paymentID") String paymentID
    ) {}

    public record ExecutePaymentResponse(
            @JsonProperty("statusCode")            String statusCode,
            @JsonProperty("statusMessage")         String statusMessage,
            @JsonProperty("paymentID")             String paymentID,
            @JsonProperty("payerReference")        String payerReference,
            @JsonProperty("customerMsisdn")        String customerMsisdn,
            @JsonProperty("trxID")                 String trxID,
            @JsonProperty("amount")                String amount,
            @JsonProperty("transactionStatus")     String transactionStatus,
            @JsonProperty("paymentExecuteTime")    String paymentExecuteTime,
            @JsonProperty("currency")              String currency,
            @JsonProperty("intent")                String intent,
            @JsonProperty("merchantInvoiceNumber") String merchantInvoiceNumber
    ) {}

    // ─────────────────────────── Query Payment ──────────────────────────────

    public record QueryPaymentResponse(
            @JsonProperty("statusCode")        String statusCode,
            @JsonProperty("statusMessage")     String statusMessage,
            @JsonProperty("paymentID")         String paymentID,
            @JsonProperty("trxID")             String trxID,
            @JsonProperty("transactionStatus") String transactionStatus,
            @JsonProperty("amount")            String amount,
            @JsonProperty("currency")          String currency,
            @JsonProperty("intent")            String intent
    ) {}

    // ─────────────────────────── Internal API response ──────────────────────

    /** Returned to the frontend after a successful createPayment call. */
    public record CheckoutInitResponse(
            String paymentID,
            String bkashURL,
            String amount
    ) {}
}
