package bd.edu.uiu.unipay.mfs;

import java.math.BigDecimal;

/**
 * Strategy interface for MFS sandbox gateways — each provider (bKash, Nagad,
 * Rocket) implements its own verification rules behind this common contract.
 */
public interface MfsGateway {

    MfsProvider provider();

    /**
     * Simulated sandbox verification of the cash-in request.
     *
     * @throws bd.edu.uiu.unipay.common.ApiException on validation failure
     *         (400 invalid input / 402 MFS-side rejection)
     */
    void verify(String mfsPhoneNumber, String otp, BigDecimal amount);
}
