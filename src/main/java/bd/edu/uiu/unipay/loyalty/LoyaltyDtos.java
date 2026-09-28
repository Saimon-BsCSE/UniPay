package bd.edu.uiu.unipay.loyalty;

import java.math.BigDecimal;

/**
 * Request / response DTOs for the Loyalty Points endpoints.
 */
public final class LoyaltyDtos {

    private LoyaltyDtos() { }

    /**
     * Balance response — also carries threshold and eligibility so the
     * frontend does not need to compute them client-side.
     */
    public record BalanceResponse(
            BigDecimal points,
            BigDecimal threshold,
            boolean eligible
    ) { }

    /**
     * Redemption response — carries all values the frontend needs to update
     * the wallet balance, the points counter, and show the success toast.
     */
    public record RedemptionResponse(
            BigDecimal pointsRedeemed,
            BigDecimal amountCredited,
            BigDecimal newWalletBalance,
            BigDecimal newPointsBalance,
            String message
    ) { }
}
