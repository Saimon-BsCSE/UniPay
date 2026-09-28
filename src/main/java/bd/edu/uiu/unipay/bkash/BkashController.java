package bd.edu.uiu.unipay.bkash;

import bd.edu.uiu.unipay.bkash.BkashDTOs.CheckoutInitResponse;
import bd.edu.uiu.unipay.bkash.BkashDTOs.QueryPaymentResponse;
import bd.edu.uiu.unipay.security.AppUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * REST controller for the real bKash Tokenized Checkout flow.
 *
 * <p><b>Protected endpoints</b> require a valid JWT (students, faculty, staff).</p>
 * <p><b>Callback endpoint</b> is public — bKash hits it without a JWT.</p>
 *
 * <pre>
 * POST /api/bkash/checkout          → initiates payment, returns bkashURL
 * GET  /api/bkash/callback          → bKash callback (public)
 * GET  /api/bkash/query/{paymentID} → query payment status (admin/debug)
 * </pre>
 */
@Tag(name = "bKash Real Checkout", description = "Real bKash Tokenized Checkout API v1.2.0-beta cash-in flow")
@RestController
@RequestMapping("/api/bkash")
@Validated
public class BkashController {

    private final BkashTokenizedService bkashService;

    public BkashController(BkashTokenizedService bkashService) {
        this.bkashService = bkashService;
    }

    // ── Step 1: Initiate Checkout ─────────────────────────────────────────────

    /**
     * Authenticated endpoint. The frontend calls this, receives the bKash
     * redirect URL, and navigates the user to it.
     *
     * @param amount   amount in BDT (20 – 50,000; up to 2 decimal places)
     * @param nonce    client-generated UUID for idempotency
     */
    @Operation(summary = "Initiate a real bKash cash-in checkout (returns bkashURL for redirect)")
    @PreAuthorize("hasAnyRole('STUDENT','FACULTY','STAFF')")
    @PostMapping("/checkout")
    public ResponseEntity<CheckoutInitResponse> initiateCheckout(
            @AuthenticationPrincipal AppUserPrincipal principal,

            @Parameter(description = "Amount in BDT (৳20 – ৳50,000)")
            @RequestParam
            @NotNull
            @DecimalMin(value = "20.0",    message = "minimum bKash cash-in is ৳20")
            @DecimalMax(value = "50000.0", message = "maximum bKash cash-in is ৳50,000")
            @Digits(integer = 5, fraction = 2, message = "amount must have at most 2 decimal places")
            BigDecimal amount,

            @Parameter(description = "Client-generated UUID for idempotency")
            @RequestParam
            @NotBlank(message = "nonce is required (client-generated UUID)")
            String nonce) {

        CheckoutInitResponse response = bkashService.initiateCheckout(principal.getId(), amount, nonce);
        return ResponseEntity.ok(response);
    }

    // ── Step 3: bKash Callback (public — no JWT) ──────────────────────────────

    /**
     * bKash POSTs/GETs to this endpoint after the user finishes on the bKash
     * payment page. This is declared public in {@code SecurityConfig}.
     *
     * <p>bKash sends: {@code paymentID}, {@code status} ("success"/"cancel"/"failure"),
     * and optionally {@code trxID} on success.</p>
     */
    @Operation(summary = "bKash payment callback (called by the bKash gateway — not for direct use)")
    @GetMapping("/callback")
    public void handleCallback(
            @RequestParam(value = "paymentID", required = false) String paymentID,
            @RequestParam(value = "status",    required = false) String status,
            HttpServletResponse httpResponse) throws IOException {

        if (paymentID == null || paymentID.isBlank()) {
            httpResponse.sendRedirect("/payment-failed?reason=missing_payment_id");
            return;
        }

        try {
            String redirectUrl = bkashService.handleCallback(paymentID, status);
            httpResponse.sendRedirect(redirectUrl);
        } catch (Exception ex) {
            // Never expose internal error details in the redirect — bKash may log it
            httpResponse.sendRedirect("/payment-failed?reason=internal_error");
        }
    }

    // ── Query (admin / debug) ─────────────────────────────────────────────────

    @Operation(summary = "Query bKash payment status by paymentID (for reconciliation)")
    @PreAuthorize("hasAnyRole('STUDENT','FACULTY','STAFF','VENDOR')")
    @GetMapping("/query/{paymentID}")
    public ResponseEntity<QueryPaymentResponse> queryPayment(
            @PathVariable String paymentID) {
        return ResponseEntity.ok(bkashService.queryPayment(paymentID));
    }
}
