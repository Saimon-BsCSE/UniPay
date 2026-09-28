package bd.edu.uiu.unipay.wallet;

import bd.edu.uiu.unipay.security.AppUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Primary wallet balance and personal transaction history.
 */
@Tag(name = "Wallet", description = "Balance and ledger history of the authenticated user")
@RestController
@RequestMapping("/api/wallet")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @Operation(summary = "Current wallet snapshot")
    @GetMapping
    public WalletDtos.WalletResponse wallet(@AuthenticationPrincipal AppUserPrincipal principal) {
        return walletService.getWallet(principal.getId());
    }

    @Operation(summary = "Personal transaction history (newest first)")
    @GetMapping("/transactions")
    public PagedModel<WalletDtos.TransactionDto> transactions(@AuthenticationPrincipal AppUserPrincipal principal,
                                                             @RequestParam(defaultValue = "0") int page,
                                                             @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)));
        return new PagedModel<>(walletService.history(principal.getId(), pageable));
    }

    @Operation(summary = "Daily expense / earnings aggregates for the tracker chart")
    @GetMapping("/expense-summary")
    public WalletDtos.ExpenseSummaryResponse expenseSummary(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam(defaultValue = "WEEKLY") String period) {
        return walletService.expenseSummary(principal.getId(), period);
    }
}

