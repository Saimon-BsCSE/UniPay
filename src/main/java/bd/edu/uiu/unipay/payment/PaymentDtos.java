package bd.edu.uiu.unipay.payment;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Request/response contracts of the money-movement endpoints.
 * Every request carries a client-generated UUID {@code nonce} — the ledger
 * primary key is derived from it, making repeated submissions idempotent.
 */
public final class PaymentDtos {

    private PaymentDtos() {
    }

    public record P2PTransferRequest(
            @NotBlank(message = "recipient (University ID or phone) is required")
            String recipient,

            @NotNull
            @DecimalMin(value = "1.0", message = "minimum transfer is ৳1")
            @DecimalMax(value = "50000.0", message = "maximum transfer is ৳50,000")
            @Digits(integer = 5, fraction = 2, message = "at most 2 decimal places")
            BigDecimal amount,

            @NotBlank(message = "nonce is required (client-generated UUID)")
            String nonce) {
    }

    public record VendorPaymentRequest(
            /** Full decoded QR payload, e.g. UNIPAY:VENDOR:V-CAFE-01[:AMT:50][:NONCE:uuid][:TS:ms] */
            String payload,
            /** Alternative to payload: manual vendor ID entry when no camera is available. */
            String vendorId,

            @NotNull
            @DecimalMin(value = "1.0", message = "minimum payment is ৳1")
            @DecimalMax(value = "50000.0", message = "maximum payment is ৳50,000")
            @Digits(integer = 5, fraction = 2, message = "at most 2 decimal places")
            BigDecimal amount,

            @NotBlank(message = "nonce is required (client-generated UUID)")
            String nonce) {
    }

    public record PaymentResponse(
            String transactionId,
            String type,
            BigDecimal amount,
            String senderId,
            String receiverId,
            String receiverName,
            BigDecimal payerNewBalance,
            String timestamp,
            boolean duplicate) {
    }
}
