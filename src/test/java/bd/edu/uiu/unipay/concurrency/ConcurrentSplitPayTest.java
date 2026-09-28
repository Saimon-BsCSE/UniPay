package bd.edu.uiu.unipay.concurrency;

import bd.edu.uiu.unipay.splitpay.SplitDtos;
import bd.edu.uiu.unipay.splitpay.SplitPayService;
import bd.edu.uiu.unipay.splitpay.SplitType;
import bd.edu.uiu.unipay.wallet.Wallet;
import bd.edu.uiu.unipay.wallet.WalletRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("h2")
class ConcurrentSplitPayTest {

    private static final String CREATOR = "0112330140";    // Saimon
    private static final String FRIEND_1 = "0112330378";   // Osama
    private static final String FRIEND_2 = "0112330586";   // Sakib

    @Autowired
    private SplitPayService splitPayService;

    @Autowired
    private WalletRepository wallets;

    @Test
    void concurrentAcceptancesConserveSystemBalance() throws Exception {
        Wallet creatorBefore = wallets.findByUser_UserId(CREATOR).orElseThrow();
        Wallet friend1Before = wallets.findByUser_UserId(FRIEND_1).orElseThrow();
        Wallet friend2Before = wallets.findByUser_UserId(FRIEND_2).orElseThrow();

        BigDecimal systemTotalBefore = creatorBefore.getCurrentBalance()
                .add(friend1Before.getCurrentBalance())
                .add(friend2Before.getCurrentBalance());

        // Create an even split bill for ৳300 total (৳100 creator, ৳100 friend1, ৳100 friend2)
        SplitDtos.CreateBillRequest req = new SplitDtos.CreateBillRequest(
                "UIU Central Canteen Lunch",
                new BigDecimal("300.00"),
                SplitType.EVEN,
                "Concurrent test",
                List.of(
                        new SplitDtos.ParticipantItem(FRIEND_1, null),
                        new SplitDtos.ParticipantItem(FRIEND_2, null)
                )
        );

        SplitDtos.BillResponse bill = splitPayService.createBill(CREATOR, req);
        assertThat(bill.participants()).hasSize(2);

        String req1Id = bill.participants().get(0).requestId();
        String req2Id = bill.participants().get(1).requestId();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        futures.add(pool.submit(() -> {
            startGun.await();
            return splitPayService.acceptRequest(FRIEND_1, req1Id);
        }));

        futures.add(pool.submit(() -> {
            startGun.await();
            return splitPayService.acceptRequest(FRIEND_2, req2Id);
        }));

        startGun.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        for (Future<?> f : futures) {
            f.get();
        }

        Wallet creatorAfter = wallets.findByUser_UserId(CREATOR).orElseThrow();
        Wallet friend1After = wallets.findByUser_UserId(FRIEND_1).orElseThrow();
        Wallet friend2After = wallets.findByUser_UserId(FRIEND_2).orElseThrow();

        BigDecimal systemTotalAfter = creatorAfter.getCurrentBalance()
                .add(friend1After.getCurrentBalance())
                .add(friend2After.getCurrentBalance());

        // System total must be exactly conserved
        assertThat(systemTotalAfter).isEqualByComparingTo(systemTotalBefore);
        // Each friend paid ৳100
        assertThat(friend1After.getCurrentBalance()).isEqualByComparingTo(friend1Before.getCurrentBalance().subtract(new BigDecimal("100.00")));
        assertThat(friend2After.getCurrentBalance()).isEqualByComparingTo(friend2Before.getCurrentBalance().subtract(new BigDecimal("100.00")));
        // Creator received ৳200 total from the two friends
        assertThat(creatorAfter.getCurrentBalance()).isEqualByComparingTo(creatorBefore.getCurrentBalance().add(new BigDecimal("200.00")));
    }
}
