package bd.edu.uiu.unipay.mfs;

import org.springframework.stereotype.Component;

/** Simulated Nagad sandbox. */
@Component
public class NagadGateway extends AbstractSimulatedMfsGateway {

    @Override
    public MfsProvider provider() {
        return MfsProvider.NAGAD;
    }

    @Override
    protected long simulateNetworkLatencyMillis() {
        return 220;
    }
}
