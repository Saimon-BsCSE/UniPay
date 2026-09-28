package bd.edu.uiu.unipay.vendor;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public final class VendorCashoutDtos {

    private VendorCashoutDtos() {
    }

    public record CashoutRequest(
            @NotNull(message = "Withdrawal amount is required")
            @DecimalMin(value = "10.00", message = "Minimum cash-out amount is ৳10.00")
            BigDecimal amount,

            @NotNull(message = "Payout channel is required")
            CashoutChannel channel,

            @NotBlank(message = "Account number or phone number is required")
            @Size(max = 50, message = "Account identifier must be <= 50 characters")
            String accountNumber,

            @Size(max = 100, message = "Account holder name must be <= 100 characters")
            String accountHolderName,

            @Size(max = 100, message = "Bank name must be <= 100 characters")
            String bankName,

            @Size(max = 100, message = "Branch name must be <= 100 characters")
            String branchName,

            String nonce
    ) {
    }

    public record CashoutResponse(
            String cashoutId,
            String transactionId,
            BigDecimal amount,
            CashoutChannel channel,
            String destination,
            BigDecimal newBalance,
            String timestamp,
            String message
    ) {
    }
}
