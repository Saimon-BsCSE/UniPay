package bd.edu.uiu.unipay.wallet;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WalletRepository extends JpaRepository<Wallet, Long> {

    Optional<Wallet> findByUser_UserId(String userId);

    /**
     * Pessimistic row lock (SELECT ... FOR UPDATE) over the target wallet.
     *
     * <p>Mandatory course requirement — used by every money-moving operation
     * (P2P transfer, vendor QR payment) so concurrent transactions cannot
     * double-spend the same wallet. The {@code lock.timeout} hint bounds the
     * wait so lock contention fails fast instead of hanging checkout threads
     * (see proposal risk matrix).</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "4000"))
    @Query("select w from Wallet w where w.user.userId = :userId")
    Optional<Wallet> findWalletForUpdateByUserId(@Param("userId") String userId);
}
