package bd.edu.uiu.unipay.mfs;

import org.springframework.stereotype.Component;

/** Simulated bKash sandbox. */
@Component
public class BkashGateway extends AbstractSimulatedMfsGateway {

    @Override
    public MfsProvider provider() {
        return MfsProvider.BKASH;
    }

    @Override
    protected long simulateNetworkLatencyMillis() {
        return 180;
    }
}
