package bd.edu.uiu.unipay.payment;

import bd.edu.uiu.unipay.security.AppUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Zero-fee money movement: P2P transfers (feature 4) and vendor QR POS
 * payments (feature 3).
 */
@Tag(name = "Payments", description = "Zero-fee P2P transfers and zero-charge vendor QR payments")
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @Operation(summary = "Send money to a campus user by University ID or phone (zero fee)")
    @PostMapping("/p2p")
    public PaymentDtos.PaymentResponse p2pTransfer(@AuthenticationPrincipal AppUserPrincipal principal,
                                                   @Valid @RequestBody PaymentDtos.P2PTransferRequest request) {
        return paymentService.p2pTransfer(principal.getId(), request);
    }

    @Operation(summary = "Pay a vendor from a scanned QR payload or manual vendor ID (zero charge)")
    @PostMapping("/vendor")
    public PaymentDtos.PaymentResponse vendorPayment(@AuthenticationPrincipal AppUserPrincipal principal,
                                                     @Valid @RequestBody PaymentDtos.VendorPaymentRequest request) {
        return paymentService.vendorPayment(principal.getId(), request);
    }
}
