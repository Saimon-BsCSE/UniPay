package bd.edu.uiu.unipay.splitpay;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

public final class SplitDtos {

    private SplitDtos() {
    }

    public record CreateBillRequest(
            @NotBlank(message = "Bill title / restaurant name is required")
            @Size(max = 100, message = "Title must be <= 100 characters")
            String title,

            @NotNull(message = "Total bill amount is required")
            @DecimalMin(value = "1.00", message = "Total bill amount must be at least ৳1.00")
            BigDecimal totalAmount,

            @NotNull(message = "Split type (EVEN or CUSTOM) is required")
            SplitType splitType,

            @Size(max = 255, message = "Note must be <= 255 characters")
            String note,

            @NotEmpty(message = "At least one participant is required")
            @Valid
            List<ParticipantItem> participants
    ) {
    }

    public record ParticipantItem(
            @NotBlank(message = "User ID or phone number is required")
            String userIdentifier,

            BigDecimal amount
    ) {
    }

    public record BillResponse(
            String billId,
            String title,
            BigDecimal totalAmount,
            SplitType splitType,
            SplitBillStatus status,
            String note,
            String creatorId,
            String creatorName,
            BigDecimal creatorShare,
            BigDecimal collectedAmount,
            BigDecimal remainingAmount,
            String createdAt,
            List<ParticipantDetail> participants
    ) {
    }

    public record ParticipantDetail(
            String requestId,
            String participantId,
            String participantName,
            BigDecimal amount,
            SplitRequestStatus status,
            String transactionId,
            String paidAt,
            String createdAt
    ) {
    }

    public record IncomingRequestResponse(
            String requestId,
            String billId,
            String billTitle,
            BigDecimal totalAmount,
            SplitType splitType,
            String creatorId,
            String creatorName,
            BigDecimal myShare,
            SplitRequestStatus status,
            String note,
            String transactionId,
            String paidAt,
            String createdAt
    ) {
    }

    public record AcceptResponse(
            String requestId,
            String transactionId,
            BigDecimal amount,
            BigDecimal payerNewBalance,
            String message
    ) {
    }

    public record PendingCountResponse(
            long pendingCount
    ) {
    }
}
