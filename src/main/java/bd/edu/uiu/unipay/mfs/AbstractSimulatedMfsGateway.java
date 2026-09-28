package bd.edu.uiu.unipay.mfs;

import bd.edu.uiu.unipay.common.ApiException;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * Template Method base for the simulated MFS sandboxes: shared phone/OTP/limit
 * validation, with a small provider-specific latency hook
 * ({@link #simulateNetworkLatencyMillis()}) to mimic real gateway behaviour.
 *
 * <p><b>Sandbox rules</b> (documented in the README): any Bangladeshi mobile
 * number ({@code 01[3-9]XXXXXXXX}) is accepted and the demo OTP is
 * {@code 123456}. No real money moves.</p>
 */
public abstract class AbstractSimulatedMfsGateway implements MfsGateway {

    /** OTP accepted by every simulated sandbox — replace with real sandbox API calls. */
    public static final String SANDBOX_OTP = "123456";

    private static final Pattern BD_MOBILE = Pattern.compile("^01[3-9]\\d{8}$");

    @Override
    public final void verify(String mfsPhoneNumber, String otp, BigDecimal amount) {
        if (mfsPhoneNumber == null || !BD_MOBILE.matcher(mfsPhoneNumber).matches()) {
            throw ApiException.badRequest(
                    "Invalid " + provider().getDisplayName() + " wallet number — expected 11-digit BD mobile number.");
        }
        if (otp == null || otp.isBlank()) {
            throw ApiException.badRequest("OTP is required by the " + provider().getDisplayName() + " sandbox.");
        }
        simulateNetworkLatency();
        if (!SANDBOX_OTP.equals(otp)) {
            throw new ApiException(402, provider().getDisplayName() + " sandbox rejected the OTP (demo OTP: 123456).");
        }
        if (amount == null
                || amount.compareTo(provider().getMinAmount()) < 0
                || amount.compareTo(provider().getMaxAmount()) > 0) {
            throw ApiException.badRequest("Amount must be between ৳%s and ৳%s for %s cash-in."
                    .formatted(provider().getMinAmount().toPlainString(),
                               provider().getMaxAmount().toPlainString(),
                               provider().getDisplayName()));
        }
    }

    /** Provider-specific simulated gateway latency. */
    protected abstract long simulateNetworkLatencyMillis();

    private void simulateNetworkLatency() {
        try {
            Thread.sleep(simulateNetworkLatencyMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
