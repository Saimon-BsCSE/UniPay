package bd.edu.uiu.unipay.mfs;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

/**
 * Request/response contracts of the MFS cash-in gateway.
 */
public final class MfsDtos {

    private MfsDtos() {
    }

    public record CashInRequest(
            @NotBlank String provider,   // BKASH | NAGAD | ROCKET

            @NotNull
            @DecimalMin(value = "20.0", message = "minimum cash-in is ৳20")
            @DecimalMax(value = "50000.0", message = "maximum cash-in is ৳50,000")
            @Digits(integer = 5, fraction = 2, message = "at most 2 decimal places")
            BigDecimal amount,

            @NotBlank
            @Pattern(regexp = "^01[3-9]\\d{8}$", message = "must be an 11-digit BD mobile number")
            String mfsPhoneNumber,

            @NotBlank(message = "OTP is required (sandbox demo OTP: 123456)")
            String otp,

            @NotBlank(message = "nonce is required (client-generated UUID)")
            String nonce) {
    }

    public record ProviderDto(String code, String name, String icon, String logoUrl,
                              BigDecimal minAmount, BigDecimal maxAmount) {
    }
}
