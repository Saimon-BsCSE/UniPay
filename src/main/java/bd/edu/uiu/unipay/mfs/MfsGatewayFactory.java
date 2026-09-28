package bd.edu.uiu.unipay.mfs;

import bd.edu.uiu.unipay.common.ApiException;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Strategy registry — collects every {@link MfsGateway} bean at startup and
 * resolves the correct implementation per provider.
 */
@Component
public class MfsGatewayFactory {

    private final Map<MfsProvider, MfsGateway> gateways = new EnumMap<>(MfsProvider.class);

    public MfsGatewayFactory(List<MfsGateway> implementations) {
        implementations.forEach(gw -> gateways.put(gw.provider(), gw));
    }

    public MfsGateway get(MfsProvider provider) {
        MfsGateway gateway = gateways.get(provider);
        if (gateway == null) {
            throw ApiException.badRequest("Unsupported MFS provider: " + provider);
        }
        return gateway;
    }
}
