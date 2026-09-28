package bd.edu.uiu.unipay.vendor;

import bd.edu.uiu.unipay.security.AppUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * Vendor dashboard API — restricted to accounts holding the VENDOR role
 * (Spring Security method-level RBAC).
 */
@Tag(name = "Vendor POS", description = "Stall profile, static/dynamic QR codes and daily sales")
@RestController
@RequestMapping("/api/vendor")
@PreAuthorize("hasRole('VENDOR')")
public class VendorController {

    private final VendorService vendorService;
    private final VendorCashoutService vendorCashoutService;

    public VendorController(VendorService vendorService, VendorCashoutService vendorCashoutService) {
        this.vendorService = vendorService;
        this.vendorCashoutService = vendorCashoutService;
    }

    @Operation(summary = "Get my stall profile (404 with VENDOR_PROFILE_INCOMPLETE until configured)")
    @GetMapping("/profile")
    public VendorDtos.VendorProfileDto profile(@AuthenticationPrincipal AppUserPrincipal principal) {
        return vendorService.getProfile(principal.getId());
    }

    @Operation(summary = "Create or update my stall profile")
    @PutMapping("/profile")
    public VendorDtos.VendorProfileDto updateProfile(@AuthenticationPrincipal AppUserPrincipal principal,
                                                     @Valid @RequestBody VendorDtos.VendorProfileRequest request) {
        return vendorService.upsertProfile(principal.getId(), request);
    }

    @Operation(summary = "Get my QR code as JSON payload (type=STATIC|DYNAMIC, optional preset amount)")
    @GetMapping("/qr")
    public VendorDtos.QrCodeResponse qr(@AuthenticationPrincipal AppUserPrincipal principal,
                                        @RequestParam(defaultValue = "STATIC") String type,
                                        @RequestParam(required = false) BigDecimal amount) {
        return "DYNAMIC".equalsIgnoreCase(type)
                ? vendorService.dynamicQr(principal.getId(), amount)
                : vendorService.staticQr(principal.getId());
    }

    @Operation(summary = "Get my QR code as a PNG image (type=STATIC|DYNAMIC, optional preset amount)")
    @GetMapping(value = "/qr.png", produces = MediaType.IMAGE_PNG_VALUE)
    public byte[] qrImage(@AuthenticationPrincipal AppUserPrincipal principal,
                          @RequestParam(defaultValue = "STATIC") String type,
                          @RequestParam(required = false) BigDecimal amount) {
        return vendorService.qrImage(principal.getId(), type, amount);
    }

    @Operation(summary = "Today's zero-fee sales total and payment count (since midnight, Dhaka)")
    @GetMapping("/stats")
    public VendorDtos.VendorStatsDto stats(@AuthenticationPrincipal AppUserPrincipal principal) {
        return vendorService.stats(principal.getId());
    }

    @Operation(summary = "Withdraw / Cash out vendor wallet earnings via MFS (bKash/Nagad/Rocket) or Bank")
    @org.springframework.web.bind.annotation.PostMapping("/cashout")
    public VendorCashoutDtos.CashoutResponse cashout(@AuthenticationPrincipal AppUserPrincipal principal,
                                                    @Valid @RequestBody VendorCashoutDtos.CashoutRequest request) {
        return vendorCashoutService.cashout(principal.getId(), request);
    }

    @Operation(summary = "Get history of vendor cash-out transactions")
    @GetMapping("/cashouts")
    public java.util.List<VendorDtos.CashoutDto> getCashouts(@AuthenticationPrincipal AppUserPrincipal principal) {
        return vendorCashoutService.getHistory(principal.getId());
    }
}
