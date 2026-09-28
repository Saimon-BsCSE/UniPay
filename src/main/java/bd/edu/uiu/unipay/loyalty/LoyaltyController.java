package bd.edu.uiu.unipay.loyalty;

import bd.edu.uiu.unipay.security.AppUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Loyalty Points REST endpoints.
 *
 * <ul>
 *   <li>{@code GET  /api/loyalty/balance} — current point balance + eligibility flag.</li>
 *   <li>{@code POST /api/loyalty/redeem}  — redeem all points for wallet credit (min 100 pts).</li>
 * </ul>
 */
@Tag(name = "Loyalty Points", description = "Earn and redeem UniPay campus loyalty points (0.25% of every spend)")
@RestController
@RequestMapping("/api/loyalty")
public class LoyaltyController {

    private final LoyaltyService loyaltyService;

    public LoyaltyController(LoyaltyService loyaltyService) {
        this.loyaltyService = loyaltyService;
    }

    @Operation(summary = "Get current loyalty point balance and eligibility")
    @GetMapping("/balance")
    public LoyaltyDtos.BalanceResponse balance(@AuthenticationPrincipal AppUserPrincipal principal) {
        return loyaltyService.getBalance(principal.getId());
    }

    @Operation(summary = "Redeem all loyalty points for wallet balance (min 100 pts, 1 pt = ৳1)")
    @PostMapping("/redeem")
    public LoyaltyDtos.RedemptionResponse redeem(@AuthenticationPrincipal AppUserPrincipal principal) {
        return loyaltyService.redeem(principal.getId());
    }
}
