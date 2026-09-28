package bd.edu.uiu.unipay.vendor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of <b>dynamic</b> (one-time) QR codes. A dynamic nonce
 * can be redeemed exactly once and expires after the configured TTL — a fresh
 * code per purchase, defeating screenshot-replay at a busy counter.
 *
 * <p>Single-node by design (campus deployment); swap for Redis if UniPay ever
 * scales horizontally.</p>
 */
@Component
public class DynamicQrCache {

    /** Registered dynamic QR entry. */
    public record Entry(String vendorId, BigDecimal presetAmount, Instant expiresAt) {
    }

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final long ttlSeconds;

    public DynamicQrCache(@Value("${app.qr.dynamic-ttl-seconds:300}") long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public String register(String vendorId, BigDecimal presetAmount) {
        purgeExpired();
        String nonce = java.util.UUID.randomUUID().toString();
        cache.put(nonce, new Entry(vendorId, presetAmount, Instant.now().plusSeconds(ttlSeconds)));
        return nonce;
    }

    /** Atomically redeems a one-time nonce; returns false when unknown/used/expired/mismatched vendor. */
    public boolean consume(String nonce, String expectedVendorId) {
        Entry entry = cache.remove(nonce);
        return entry != null
                && entry.vendorId().equals(expectedVendorId)
                && entry.expiresAt().isAfter(Instant.now());
    }

    @Scheduled(fixedDelay = 60_000)
    void purgeExpired() {
        Instant now = Instant.now();
        cache.values().removeIf(entry -> !entry.expiresAt().isAfter(now));
    }
}
