package bd.edu.uiu.unipay.otp;

import bd.edu.uiu.unipay.common.ApiException;
import bd.edu.uiu.unipay.mfs.AbstractSimulatedMfsGateway;
import bd.edu.uiu.unipay.mfs.MfsDtos;
import bd.edu.uiu.unipay.mfs.MfsService;
import bd.edu.uiu.unipay.payment.PaymentDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * OTP Verification Service — handles two-step MFS cash-in.
 *
 * <h3>Security properties</h3>
 * <ul>
 *   <li>OTPs are generated with {@link SecureRandom} and stored as BCrypt hashes.</li>
 *   <li>Rate limit: max {@value #MAX_GENERATES_PER_WINDOW} generations per
 *       {@value #RATE_WINDOW_MINUTES}-minute window per user.</li>
 *   <li>Each OTP record allows at most {@value #MAX_VERIFY_ATTEMPTS} verify
 *       attempts before being permanently locked.</li>
 *   <li>All OTP records have a hard TTL of {@value #OTP_TTL_SECONDS} seconds.</li>
 * </ul>
 *
 * <p>In sandbox mode the 6-digit OTP is also logged at INFO level so that
 * demo users can read it from the console — no real SMS gateway is called.</p>
 */
@Service
public class OtpService {

    private static final Logger log = LoggerFactory.getLogger(OtpService.class);

    static final int OTP_TTL_SECONDS        = 180;   // 3 minutes
    static final int MAX_GENERATES_PER_WINDOW = 3;
    static final int RATE_WINDOW_MINUTES    = 15;
    static final int MAX_VERIFY_ATTEMPTS    = 3;

    private final OtpRepository otpRepository;
    private final MfsService mfsService;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();

    public OtpService(OtpRepository otpRepository,
                      MfsService mfsService,
                      PasswordEncoder passwordEncoder) {
        this.otpRepository = otpRepository;
        this.mfsService = mfsService;
        this.passwordEncoder = passwordEncoder;
    }

    // ---------------------------------------------------------------- generate

    /**
     * Generates a 6-digit OTP, hashes it with BCrypt, persists the record, and
     * returns the record ID + expiry so the client can start its countdown timer.
     *
     * <p>In this sandbox implementation the plaintext OTP is also logged at
     * {@code INFO} level to simulate delivery via the phone's SMS inbox.</p>
     *
     * @throws ApiException 429 if the user exceeds the rate limit
     */
    @Transactional
    public OtpDtos.GenerateResponse generate(String userId, OtpDtos.GenerateRequest req) {
        // Rate-limit check
        Instant windowStart = Instant.now().minus(RATE_WINDOW_MINUTES, ChronoUnit.MINUTES);
        int recentCount = otpRepository.countByUserIdAndCreatedAtAfter(userId, windowStart);
        if (recentCount >= MAX_GENERATES_PER_WINDOW) {
            throw new ApiException(429,
                    "Too many OTP requests. Please wait before requesting a new OTP (max "
                    + MAX_GENERATES_PER_WINDOW + " per " + RATE_WINDOW_MINUTES + " minutes).");
        }

        // Generate 6-digit OTP
        String plainOtp = String.format("%06d", secureRandom.nextInt(1_000_000));
        String hashedOtp = passwordEncoder.encode(plainOtp);

        Instant expiresAt = Instant.now().plus(OTP_TTL_SECONDS, ChronoUnit.SECONDS);
        OtpRecord record = new OtpRecord(
                userId,
                req.provider().toUpperCase(),
                req.mfsPhoneNumber(),
                hashedOtp,
                req.amount(),
                req.nonce(),
                expiresAt
        );
        OtpRecord saved = otpRepository.save(record);

        // In production: replace this log with a real SMS gateway call (Twilio / BulkSMS BD).
        // The OTP is logged at DEBUG only — not visible unless debug logging is enabled.
        log.debug("[OTP] User={} | Provider={} | Phone={} | Amount={} | OTP={} | Expires={}",
                userId, req.provider(), maskPhone(req.mfsPhoneNumber()),
                req.amount(), plainOtp, expiresAt);
        // Print to INFO with masked OTP so admins can still trace flow
        log.info("[OTP SENT] User={} | Provider={} | Phone={} | Amount={}",
                userId, req.provider(), maskPhone(req.mfsPhoneNumber()), req.amount());

        return new OtpDtos.GenerateResponse(
                saved.getId(),
                expiresAt.toString(),
                maskPhone(req.mfsPhoneNumber()),
                OTP_TTL_SECONDS
        );
    }

    // ---------------------------------------------------------------- verify

    /**
     * Verifies the submitted OTP digit string and — if valid — atomically
     * delegates to {@link MfsService#cashIn} to credit the user's wallet.
     *
     * @throws ApiException 400 / 401 / 410 on verification failures
     */
    @Transactional
    public OtpDtos.VerifyResponse verifyAndCashIn(String userId, OtpDtos.VerifyRequest req) {
        OtpRecord record = otpRepository
                .findValidById(req.otpId(), userId, Instant.now())
                .orElseThrow(() -> new ApiException(410,
                        "OTP has expired or does not exist. Please request a new one."));

        if (record.isLocked()) {
            throw new ApiException(429,
                    "Maximum verification attempts exceeded. Please request a new OTP.");
        }

        // Verify with hashed OTP, or accept sandbox demo OTP "123456"
        boolean matches = AbstractSimulatedMfsGateway.SANDBOX_OTP.equals(req.otp())
                || passwordEncoder.matches(req.otp(), record.getOtpHash());

        if (!matches) {
            record.incrementAttempts();
            otpRepository.save(record);
            int remaining = record.getMaxAttempts() - record.getAttempts();
            throw new ApiException(401,
                    "Incorrect OTP. " + remaining + " attempt" + (remaining == 1 ? "" : "s") + " remaining.");
        }

        // OTP matched — perform the cash-in inside this same transaction
        MfsDtos.CashInRequest cashInReq = new MfsDtos.CashInRequest(
                record.getProvider(),
                record.getAmount(),
                record.getPhoneNumber(),
                AbstractSimulatedMfsGateway.SANDBOX_OTP,
                record.getNonce()
        );
        PaymentDtos.PaymentResponse payResp = mfsService.cashIn(userId, cashInReq);

        // Mark OTP as fully consumed (attempts = maxAttempts) so it cannot be reused
        record.incrementAttempts();
        record.incrementAttempts(); // push to maxAttempts so isLocked() is true
        record.incrementAttempts();
        otpRepository.save(record);

        return new OtpDtos.VerifyResponse(
                true,
                payResp.transactionId(),
                payResp.amount(),
                payResp.payerNewBalance(),
                "৳" + payResp.amount() + " added to your UniPay wallet successfully!"
        );
    }

    // ---------------------------------------------------------------- helpers

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 6) return "***";
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 3);
    }
}
