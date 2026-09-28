package bd.edu.uiu.unipay.vendor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Request/response contracts of the vendor dashboard endpoints.
 */
public final class VendorDtos {

    private VendorDtos() {
    }

    public record VendorProfileRequest(
            @NotBlank @Size(max = 100) String stallName,
            @NotNull StallCategory stallCategory) {
    }

    public record VendorProfileDto(String vendorId,
                                   String stallName,
                                   String stallCategory,
                                   String qrCodeIdentifier) {
    }

    /** type: STATIC (reusable) | DYNAMIC (one-time nonce, TTL bounded). */
    public record QrCodeResponse(String type,
                                 String vendorId,
                                 String payload,
                                 BigDecimal presetAmount,
                                 String expiresAt) {
    }

    public record VendorStatsDto(BigDecimal todaySales, long todayCount) {
    }

    /** Safe serializable DTO for a vendor cash-out record (avoids lazy User proxy). */
    public record CashoutDto(
            String cashoutId,
            String vendorId,
            java.math.BigDecimal amount,
            String channel,
            String accountNumber,
            String accountHolderName,
            String bankName,
            String branchName,
            String transactionId,
            String status,
            String createdAt) {
    }
}
