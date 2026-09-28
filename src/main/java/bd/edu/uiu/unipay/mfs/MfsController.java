package bd.edu.uiu.unipay.mfs;

import bd.edu.uiu.unipay.payment.PaymentDtos;
import bd.edu.uiu.unipay.security.AppUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * MFS Online Cash-In Gateway (feature 2) — simulated sandboxes, zero charge.
 */
@Tag(name = "MFS Cash-In", description = "Top up via simulated bKash / Nagad / Rocket sandboxes")
@RestController
@RequestMapping("/api/mfs")
public class MfsController {

    private final MfsService mfsService;

    public MfsController(MfsService mfsService) {
        this.mfsService = mfsService;
    }

    @Operation(summary = "List the supported MFS providers and their limits")
    @GetMapping("/providers")
    public List<MfsDtos.ProviderDto> providers() {
        return mfsService.providers();
    }

    @Operation(summary = "Add funds to the wallet via a simulated MFS sandbox (zero service charge)")
    @PostMapping("/cash-in")
    public PaymentDtos.PaymentResponse cashIn(@AuthenticationPrincipal AppUserPrincipal principal,
                                              @Valid @RequestBody MfsDtos.CashInRequest request) {
        return mfsService.cashIn(principal.getId(), request);
    }
}
