package bd.edu.uiu.unipay.otp;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Request / response DTOs for the OTP endpoints.
 */
public final class OtpDtos {

    private OtpDtos() { }

    /** Step 1 — client asks the backend to generate and (simulated-)send an OTP. */
    public record GenerateRequest(

            @NotBlank(message = "Provider is required (BKASH | NAGAD | ROCKET)")
            String provider,

            @NotBlank(message = "MFS phone number is required")
            @Pattern(regexp = "^01[3-9]\\d{8}$", message = "Must be an 11-digit BD mobile number")
            String mfsPhoneNumber,

            @NotNull(message = "Deposit amount is required")
            @DecimalMin(value = "20.0", message = "Minimum cash-in is ৳20")
            @DecimalMax(value = "50000.0", message = "Maximum cash-in is ৳50,000")
            @Digits(integer = 5, fraction = 2)
            BigDecimal amount,

            @NotBlank(message = "A client-generated UUID nonce is required")
            String nonce
    ) { }

    /** Step 1 — backend response containing the OTP record ID and expiry. */
    public record GenerateResponse(
            Long otpId,
            String expiresAt,
            String maskedPhone,
            /** seconds remaining until the OTP expires (always 180) */
            int ttlSeconds
    ) { }

    /** Step 2 — client submits the 6-digit code for verification. */
    public record VerifyRequest(
            @NotNull(message = "otpId is required")
            Long otpId,

            @NotBlank(message = "OTP code is required")
            @Size(min = 6, max = 6, message = "OTP must be exactly 6 digits")
            @Pattern(regexp = "\\d{6}", message = "OTP must be 6 digits")
            String otp
    ) { }

    /** Step 2 — backend response after a successful verify + cash-in. */
    public record VerifyResponse(
            boolean verified,
            String transactionId,
            BigDecimal amount,
            BigDecimal newBalance,
            String message
    ) { }
}
