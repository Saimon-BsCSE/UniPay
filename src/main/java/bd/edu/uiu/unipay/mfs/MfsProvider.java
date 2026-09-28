package bd.edu.uiu.unipay.mfs;

import java.math.BigDecimal;

/**
 * Supported simulated MFS (Mobile Financial Services) cash-in channels.
 * Cash-out is intentionally NOT offered — enclosed campus economy.
 */
public enum MfsProvider {
    BKASH("bKash", "🟣", "/img/bkash.svg", 20, 50_000),
    NAGAD("Nagad", "🟠", "/img/nagad.svg", 20, 50_000),
    ROCKET("Rocket", "🟣", "/img/rocket.svg", 20, 50_000);

    private final String displayName;
    private final String icon;
    private final String logoUrl;
    private final BigDecimal minAmount;
    private final BigDecimal maxAmount;

    MfsProvider(String displayName, String icon, String logoUrl, int minAmount, int maxAmount) {
        this.displayName = displayName;
        this.icon = icon;
        this.logoUrl = logoUrl;
        this.minAmount = BigDecimal.valueOf(minAmount);
        this.maxAmount = BigDecimal.valueOf(maxAmount);
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getIcon() {
        return icon;
    }

    public String getLogoUrl() {
        return logoUrl;
    }

    public BigDecimal getMinAmount() {
        return minAmount;
    }

    public BigDecimal getMaxAmount() {
        return maxAmount;
    }

    public static MfsProvider fromCode(String code) {
        if (code == null) {
            return null;
        }
        try {
            return MfsProvider.valueOf(code.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
