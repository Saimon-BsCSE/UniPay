package bd.edu.uiu.unipay.vendor;

import bd.edu.uiu.unipay.common.ApiException;
import bd.edu.uiu.unipay.transaction.TransactionRepository;
import bd.edu.uiu.unipay.user.User;
import bd.edu.uiu.unipay.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Vendor merchant accounts (feature 1) and the QR-based POS (feature 3):
 * stall profiles, static QR identifiers, one-time dynamic QRs, end-of-day
 * sales totals and rendered QR images.
 */
@Service
public class VendorService {

    private static final ZoneId DHAKA = ZoneId.of("Asia/Dhaka");

    private final VendorProfileRepository vendorProfiles;
    private final UserRepository users;
    private final TransactionRepository transactions;
    private final DynamicQrCache dynamicQrCache;
    private final QrImageService qrImageService;

    public VendorService(VendorProfileRepository vendorProfiles,
                         UserRepository users,
                         TransactionRepository transactions,
                         DynamicQrCache dynamicQrCache,
                         QrImageService qrImageService) {
        this.vendorProfiles = vendorProfiles;
        this.users = users;
        this.transactions = transactions;
        this.dynamicQrCache = dynamicQrCache;
        this.qrImageService = qrImageService;
    }

    @Transactional(readOnly = true)
    public VendorDtos.VendorProfileDto getProfile(String vendorId) {
        return vendorProfiles.findById(vendorId)
                .map(this::toDto)
                .orElseThrow(() -> new ApiException(404, "VENDOR_PROFILE_INCOMPLETE"));
    }

    @Transactional
    public VendorDtos.VendorProfileDto upsertProfile(String vendorId, VendorDtos.VendorProfileRequest request) {
        User vendor = users.findById(vendorId)
                .orElseThrow(() -> ApiException.notFound("Unknown vendor account."));
        VendorProfile profile = vendorProfiles.findById(vendorId)
                .orElseGet(() -> new VendorProfile(vendor, request.stallName().trim(), request.stallCategory()));
        profile.setStallName(request.stallName().trim());
        profile.setStallCategory(request.stallCategory());
        return toDto(vendorProfiles.save(profile));
    }

    /** Static QR: stable payload from Vendor_Profiles.qr_code_identifier — works forever. */
    @Transactional(readOnly = true)
    public VendorDtos.QrCodeResponse staticQr(String vendorId) {
        VendorProfile profile = requireProfile(vendorId);
        return new VendorDtos.QrCodeResponse("STATIC", vendorId, profile.getQrCodeIdentifier(), null, null);
    }

    /**
     * Dynamic QR: fresh one-time nonce, optionally presetting the exact amount.
     * Payload: {@code UNIPAY:VENDOR:<id>[:AMT:<amount>]:NONCE:<uuid>:TS:<millis>}
     */
    @Transactional(readOnly = true)
    public VendorDtos.QrCodeResponse dynamicQr(String vendorId, BigDecimal presetAmount) {
        requireProfile(vendorId);
        String nonce = dynamicQrCache.register(vendorId, presetAmount);
        StringBuilder payload = new StringBuilder("UNIPAY:VENDOR:").append(vendorId);
        if (presetAmount != null) {
            payload.append(":AMT:").append(presetAmount.toPlainString());
        }
        payload.append(":NONCE:").append(nonce)
               .append(":TS:").append(System.currentTimeMillis());
        String expiresAt = Instant.now().plusSeconds(dynamicQrCache.getTtlSeconds()).toString();
        return new VendorDtos.QrCodeResponse("DYNAMIC", vendorId, payload.toString(), presetAmount, expiresAt);
    }

    /** PNG rendering of the current static or dynamic QR payload. */
    @Transactional(readOnly = true)
    public byte[] qrImage(String vendorId, String type, BigDecimal presetAmount) {
        VendorDtos.QrCodeResponse qr = "DYNAMIC".equalsIgnoreCase(type)
                ? dynamicQr(vendorId, presetAmount)
                : staticQr(vendorId);
        return qrImageService.renderPng(qr.payload(), 320);
    }

    /** End-of-day bookkeeping replacement: total zero-fee sales collected since midnight (Dhaka). */
    @Transactional(readOnly = true)
    public VendorDtos.VendorStatsDto stats(String vendorId) {
        requireProfile(vendorId);
        Instant startOfDay = LocalDate.now(DHAKA).atStartOfDay(DHAKA).toInstant();
        BigDecimal sales = transactions.sumVendorPaymentsSince(vendorId, startOfDay);
        long count = transactions.countVendorPaymentsSince(vendorId, startOfDay);
        return new VendorDtos.VendorStatsDto(sales, count);
    }

    private VendorProfile requireProfile(String vendorId) {
        return vendorProfiles.findById(vendorId)
                .orElseThrow(() -> new ApiException(404, "VENDOR_PROFILE_INCOMPLETE"));
    }

    private VendorDtos.VendorProfileDto toDto(VendorProfile profile) {
        return new VendorDtos.VendorProfileDto(
                profile.getVendorId(),
                profile.getStallName(),
                profile.getStallCategory().name(),
                profile.getQrCodeIdentifier());
    }
}
