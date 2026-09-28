package bd.edu.uiu.unipay.otp;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/**
 * Data-access layer for {@link OtpRecord}.
 */
@Repository
public interface OtpRepository extends JpaRepository<OtpRecord, Long> {

    /**
     * Counts how many OTP generation requests the user has made since the
     * given cutoff — used to enforce the max-3-per-15-min rate limit.
     */
    int countByUserIdAndCreatedAtAfter(String userId, Instant cutoff);

    /**
     * Returns the most recent valid (non-expired) OTP record for this
     * user so the service can verify the submitted digit string.
     */
    @Query("SELECT o FROM OtpRecord o WHERE o.id = :id AND o.userId = :userId AND o.expiresAt > :now")
    Optional<OtpRecord> findValidById(@Param("id") Long id,
                                      @Param("userId") String userId,
                                      @Param("now") Instant now);
}
