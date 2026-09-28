package bd.edu.uiu.unipay.loyalty;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Data-access layer for {@link LoyaltyAccount}.
 */
@Repository
public interface LoyaltyRepository extends JpaRepository<LoyaltyAccount, Long> {

    Optional<LoyaltyAccount> findByUserId(String userId);

    /**
     * Acquires a PESSIMISTIC_WRITE (SELECT … FOR UPDATE) lock on the
     * loyalty row before a redemption operation to prevent race conditions
     * where two concurrent redemptions could both pass the balance check.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM LoyaltyAccount a WHERE a.userId = :userId")
    Optional<LoyaltyAccount> findByUserIdForUpdate(@Param("userId") String userId);
}
