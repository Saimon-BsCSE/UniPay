package bd.edu.uiu.unipay.concurrency;

import bd.edu.uiu.unipay.payment.PaymentDtos;
import bd.edu.uiu.unipay.payment.PaymentService;
import bd.edu.uiu.unipay.wallet.Wallet;
import bd.edu.uiu.unipay.wallet.WalletRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency proof (small scale, mirrors the JMeter/virtual-thread strategy
 * from the proposal): simultaneous transfers in <b>both directions</b> between
 * the same two wallets. PESSIMISTIC_WRITE row locks + ascending user-ID lock
 * ordering must guarantee zero lost updates and zero deadlocks.
 */
@SpringBootTest
@ActiveProfiles("h2")
class ConcurrentP2PTest {

    private static final String USER_A = "0112330140"; // Saimon, seeded ৳1500
    private static final String USER_B = "0112330378"; // Osama,  seeded ৳1200

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private WalletRepository wallets;

    @Test
    void simultaneousBidirectionalTransfersKeepBalancesExact() throws Exception {
        Wallet beforeA = wallets.findByUser_UserId(USER_A).orElseThrow();
        Wallet beforeB = wallets.findByUser_UserId(USER_B).orElseThrow();

        int transfersPerDirection = 25;
        BigDecimal amount = new BigDecimal("5.00");
        BigDecimal expectedDrift = amount.multiply(BigDecimal.valueOf(transfersPerDirection)); // net zero

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<?>> jobs = new ArrayList<>();

        for (int i = 0; i < transfersPerDirection; i++) {
            jobs.add(pool.submit(transfer(startGun, USER_A, USER_B, amount)));
            jobs.add(pool.submit(transfer(startGun, USER_B, USER_A, amount)));
        }

        startGun.countDown(); // fire all 50 transfers at once
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        jobs.forEach(f -> {
            try { f.get(); }
            catch (Exception e) { throw new AssertionError("A concurrent transfer failed: " + e.getMessage(), e); }
        });

        Wallet afterA = wallets.findByUser_UserId(USER_A).orElseThrow();
        Wallet afterB = wallets.findByUser_UserId(USER_B).orElseThrow();

        // Total money in the closed system must be conserved exactly.
        BigDecimal totalBefore = beforeA.getCurrentBalance().add(beforeB.getCurrentBalance());
        BigDecimal totalAfter = afterA.getCurrentBalance().add(afterB.getCurrentBalance());
        assertThat(totalAfter).isEqualByComparingTo(totalBefore);

        // Net zero because both directions moved identical totals — to the cent.
        assertThat(afterA.getCurrentBalance())
                .isEqualByComparingTo(beforeA.getCurrentBalance().subtract(expectedDrift).add(expectedDrift));
        assertThat(afterB.getCurrentBalance()).isEqualByComparingTo(beforeB.getCurrentBalance());
    }

    private Runnable transfer(CountDownLatch gun, String from, String to, BigDecimal amount) {
        return () -> {
            try {
                gun.await();
                paymentService.p2pTransfer(from, new PaymentDtos.P2PTransferRequest(
                        to, amount, UUID.randomUUID().toString()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }
}
