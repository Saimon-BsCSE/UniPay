package bd.edu.uiu.unipay.mfs;

import org.springframework.stereotype.Component;

/** Simulated Rocket (DBBL) sandbox. */
@Component
public class RocketGateway extends AbstractSimulatedMfsGateway {

    @Override
    public MfsProvider provider() {
        return MfsProvider.ROCKET;
    }

    @Override
    protected long simulateNetworkLatencyMillis() {
        return 260;
    }
}
