package bd.edu.uiu.unipay.splitpay;

import bd.edu.uiu.unipay.security.AppUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller for SplitPay — splitting restaurant bills & handling money requests.
 */
@Tag(name = "SplitPay", description = "Split restaurant bills and request money contributions from campus peers")
@RestController
@RequestMapping("/api/splitpay")
public class SplitPayController {

    private final SplitPayService splitPayService;

    public SplitPayController(SplitPayService splitPayService) {
        this.splitPayService = splitPayService;
    }

    @Operation(summary = "Create a new SplitPay bill and send money requests to friends")
    @PostMapping("/bills")
    public SplitDtos.BillResponse createBill(@AuthenticationPrincipal AppUserPrincipal principal,
                                             @Valid @RequestBody SplitDtos.CreateBillRequest request) {
        return splitPayService.createBill(principal.getId(), request);
    }

    @Operation(summary = "Get all SplitPay bills created by the authenticated user")
    @GetMapping("/my-bills")
    public List<SplitDtos.BillResponse> getMyBills(@AuthenticationPrincipal AppUserPrincipal principal) {
        return splitPayService.getMyBills(principal.getId());
    }

    @Operation(summary = "Get all incoming SplitPay money requests for the authenticated user")
    @GetMapping("/my-requests")
    public List<SplitDtos.IncomingRequestResponse> getMyRequests(@AuthenticationPrincipal AppUserPrincipal principal) {
        return splitPayService.getMyRequests(principal.getId());
    }

    @Operation(summary = "Get the count of pending incoming requests (for notification badges)")
    @GetMapping("/pending-count")
    public SplitDtos.PendingCountResponse getPendingCount(@AuthenticationPrincipal AppUserPrincipal principal) {
        return new SplitDtos.PendingCountResponse(splitPayService.getPendingCount(principal.getId()));
    }

    @Operation(summary = "Accept a split request and deduct the amount from wallet")
    @PostMapping("/requests/{requestId}/accept")
    public SplitDtos.AcceptResponse acceptRequest(@AuthenticationPrincipal AppUserPrincipal principal,
                                                 @PathVariable String requestId) {
        return splitPayService.acceptRequest(principal.getId(), requestId);
    }

    @Operation(summary = "Decline a split request")
    @PostMapping("/requests/{requestId}/decline")
    public void declineRequest(@AuthenticationPrincipal AppUserPrincipal principal,
                               @PathVariable String requestId) {
        splitPayService.declineRequest(principal.getId(), requestId);
    }

    @Operation(summary = "Cancel an active split bill created by the user")
    @PostMapping("/bills/{billId}/cancel")
    public void cancelBill(@AuthenticationPrincipal AppUserPrincipal principal,
                           @PathVariable String billId) {
        splitPayService.cancelBill(principal.getId(), billId);
    }
}
