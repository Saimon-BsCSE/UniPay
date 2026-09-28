package bd.edu.uiu.unipay.otp;

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
 * Two-step OTP endpoints for the MFS cash-in flow.
 *
 * <ul>
 *   <li>{@code POST /api/otp/generate} — Step 1: generate a 6-digit OTP and
 *       start the 3-minute countdown.</li>
 *   <li>{@code POST /api/otp/verify}   — Step 2: verify the submitted code and
 *       atomically credit the wallet.</li>
 * </ul>
 */
@Tag(name = "OTP", description = "Two-step OTP verification for MFS cash-in (Add Money)")
@RestController
@RequestMapping("/api/otp")
public class OtpController {

    private final OtpService otpService;

    public OtpController(OtpService otpService) {
        this.otpService = otpService;
    }

    @Operation(summary = "Generate a 6-digit OTP for MFS cash-in (Step 1)")
    @PostMapping("/generate")
    public OtpDtos.GenerateResponse generate(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody OtpDtos.GenerateRequest request) {
        return otpService.generate(principal.getId(), request);
    }

    @Operation(summary = "Verify the OTP and credit the wallet atomically (Step 2)")
    @PostMapping("/verify")
    public OtpDtos.VerifyResponse verify(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody OtpDtos.VerifyRequest request) {
        return otpService.verifyAndCashIn(principal.getId(), request);
    }
}
