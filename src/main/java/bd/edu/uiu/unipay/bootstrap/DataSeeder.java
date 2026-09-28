package bd.edu.uiu.unipay.bootstrap;

import bd.edu.uiu.unipay.bkash.BkashTokenizedService;
import bd.edu.uiu.unipay.loyalty.LoyaltyAccount;
import bd.edu.uiu.unipay.loyalty.LoyaltyRepository;
import bd.edu.uiu.unipay.transaction.Transaction;
import bd.edu.uiu.unipay.transaction.TransactionRepository;
import bd.edu.uiu.unipay.transaction.TransactionType;
import bd.edu.uiu.unipay.user.Role;
import bd.edu.uiu.unipay.user.User;
import bd.edu.uiu.unipay.user.UserRepository;
import bd.edu.uiu.unipay.vendor.StallCategory;
import bd.edu.uiu.unipay.vendor.VendorProfile;
import bd.edu.uiu.unipay.vendor.VendorProfileRepository;
import bd.edu.uiu.unipay.wallet.Wallet;
import bd.edu.uiu.unipay.wallet.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Seeds a full campus sandbox with students, faculty, staff, vendors, and 30 full days
 * of realistic transaction history across all roles for expense and revenue tracking.
 */
@Component
@Order(10)
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);
    private static final String DEMO_PASSWORD = "demo1234";

    private final UserRepository users;
    private final WalletRepository wallets;
    private final VendorProfileRepository vendorProfiles;
    private final TransactionRepository transactions;
    private final PasswordEncoder passwordEncoder;
    private final LoyaltyRepository loyaltyRepository;

    public DataSeeder(UserRepository users,
                      WalletRepository wallets,
                      VendorProfileRepository vendorProfiles,
                      TransactionRepository transactions,
                      PasswordEncoder passwordEncoder,
                      LoyaltyRepository loyaltyRepository) {
        this.users = users;
        this.wallets = wallets;
        this.vendorProfiles = vendorProfiles;
        this.transactions = transactions;
        this.passwordEncoder = passwordEncoder;
        this.loyaltyRepository = loyaltyRepository;
    }

    private record Seed(String userId, String fullName, String phone, Role role,
                        String stallName, StallCategory category, BigDecimal openingBalance) {
    }

    private static final List<Seed> SEEDS = List.of(
            new Seed("0112330140", "Md Saimon Islam",    "01712345640", Role.STUDENT, null, null, new BigDecimal("8500.00")),
            new Seed("0112330378", "Osama Bin Mansur",   "01712345641", Role.STUDENT, null, null, new BigDecimal("8500.00")),
            new Seed("0112330586", "Md Sadman Sakib",    "01712345642", Role.STUDENT, null, null, new BigDecimal("6000.00")),
            new Seed("0111910667", "Dr. Nafees Ahmed",   "01712345643", Role.FACULTY, null, null, new BigDecimal("14000.00")),
            new Seed("UIU-STF-101","Rezaul Karim",       "01712345644", Role.STAFF,   null, null, new BigDecimal("5000.00")),
            new Seed("V-CAFE-01",  "UIU Central Canteen","01712345645", Role.VENDOR,  "UIU Central Canteen",   StallCategory.CANTEEN,   BigDecimal.ZERO),
            new Seed("V-BOOK-01",  "UIU Book Shop",      "01712345646", Role.VENDOR,  "UIU Book Shop",         StallCategory.BOOKSHOP,  BigDecimal.ZERO),
            new Seed("V-FOOD-01",  "Campus Samosa Corner","01712345647",Role.VENDOR,  "Campus Samosa Corner",  StallCategory.FOOD_STALL,BigDecimal.ZERO));

    @Override
    @Transactional
    public void run(String... args) {
        // ── Always ensure BKASH_GATEWAY system user exists ─────────────────────
        ensureBkashGatewayUser();

        if (users.count() > 1) {   // > 1 because BKASH_GATEWAY may already be present
            return;                 // already seeded / production data present
        }

        String hash = passwordEncoder.encode(DEMO_PASSWORD);
        int seq = 1;
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        // ── 1. Create all users, wallets, vendor profiles, loyalty accounts ────
        for (Seed seed : SEEDS) {
            User user = users.save(new User(seed.userId(), seed.fullName(), seed.phone(), hash, seed.role()));
            Wallet wallet = wallets.save(new Wallet(user));

            if (seed.role() == Role.VENDOR) {
                vendorProfiles.save(new VendorProfile(user, seed.stallName(), seed.category()));
            } else {
                // Seed demo bonus loyalty points so users can test redemption
                LoyaltyAccount account = new LoyaltyAccount(seed.userId());
                account.addPoints(new BigDecimal("25.0000"));
                loyaltyRepository.save(account);

                if (seed.openingBalance().compareTo(BigDecimal.ZERO) > 0) {
                    wallet.credit(seed.openingBalance());
                    // Opening balance MFS seed transaction backdated 31 days
                    Transaction openTxn = new Transaction(
                            "TXN-SEED-%03d-%s".formatted(seq++, seed.userId()),
                            user, user, seed.openingBalance(), TransactionType.MFS_CASH_IN);
                    setTimestamp(openTxn, today.minusDays(31).atTime(9, 0).toInstant(ZoneOffset.UTC));
                    transactions.save(openTxn);
                }
            }
        }

        // ── 2. Seed 30-day historical expense & earnings transactions ──────────
        User saimon   = users.findById("0112330140").orElseThrow();
        User osama    = users.findById("0112330378").orElseThrow();
        User sakib    = users.findById("0112330586").orElseThrow();
        User nafees   = users.findById("0111910667").orElseThrow();
        User rezaul   = users.findById("UIU-STF-101").orElseThrow();
        User canteen  = users.findById("V-CAFE-01").orElseThrow();
        User bookShop = users.findById("V-BOOK-01").orElseThrow();
        User samosa   = users.findById("V-FOOD-01").orElseThrow();

        record HistEntry(int daysAgo, int hour, int minute, User sender, User receiver, BigDecimal amount, TransactionType type) {}
        List<HistEntry> history = new ArrayList<>();

        for (int day = 29; day >= 0; day--) {
            // UIU Central Canteen (Lunch / Meals) - All roles participate daily
            history.add(new HistEntry(day, 12, 15, saimon,  canteen, BigDecimal.valueOf(65 + (day * 7) % 60),  TransactionType.VENDOR_PAYMENT));
            history.add(new HistEntry(day, 12, 40, osama,   canteen, BigDecimal.valueOf(60 + (day * 11) % 55), TransactionType.VENDOR_PAYMENT));
            history.add(new HistEntry(day, 13, 05, sakib,   canteen, BigDecimal.valueOf(50 + (day * 13) % 45), TransactionType.VENDOR_PAYMENT));
            history.add(new HistEntry(day, 13, 30, nafees,  canteen, BigDecimal.valueOf(90 + (day * 17) % 70), TransactionType.VENDOR_PAYMENT));
            history.add(new HistEntry(day, 13, 50, rezaul,  canteen, BigDecimal.valueOf(45 + (day * 5) % 35),  TransactionType.VENDOR_PAYMENT));

            // Campus Samosa Corner (Snacks / Evening tea) - Daily snacks
            history.add(new HistEntry(day, 16, 20, saimon,  samosa,  BigDecimal.valueOf(30 + (day * 3) % 25),  TransactionType.VENDOR_PAYMENT));
            history.add(new HistEntry(day, 16, 35, osama,   samosa,  BigDecimal.valueOf(25 + (day * 7) % 30),  TransactionType.VENDOR_PAYMENT));
            history.add(new HistEntry(day, 16, 50, sakib,   samosa,  BigDecimal.valueOf(20 + (day * 5) % 25),  TransactionType.VENDOR_PAYMENT));
            history.add(new HistEntry(day, 17, 10, nafees,  samosa,  BigDecimal.valueOf(35 + (day * 9) % 30),  TransactionType.VENDOR_PAYMENT));
            history.add(new HistEntry(day, 17, 25, rezaul,  samosa,  BigDecimal.valueOf(25 + (day * 4) % 20),  TransactionType.VENDOR_PAYMENT));

            // UIU Book Shop (Stationery, Lab Sheets, Books, Reference Notes)
            if (day % 3 == 0) {
                history.add(new HistEntry(day, 14, 10, saimon, bookShop, BigDecimal.valueOf(120 + (day * 13) % 150), TransactionType.VENDOR_PAYMENT));
            }
            if (day % 3 == 1) {
                history.add(new HistEntry(day, 14, 30, osama,  bookShop, BigDecimal.valueOf(140 + (day * 17) % 160), TransactionType.VENDOR_PAYMENT));
            }
            if (day % 4 == 0) {
                history.add(new HistEntry(day, 14, 45, sakib,  bookShop, BigDecimal.valueOf(80 + (day * 7) % 100),   TransactionType.VENDOR_PAYMENT));
            }
            if (day % 2 == 0) {
                history.add(new HistEntry(day, 15, 15, nafees, bookShop, BigDecimal.valueOf(220 + (day * 23) % 220), TransactionType.VENDOR_PAYMENT));
            }

            // Peer-to-Peer Transfers (Friends sharing expenses, splitting meals)
            if (day % 3 == 0) {
                history.add(new HistEntry(day, 18, 00, osama,  saimon, BigDecimal.valueOf(75 + (day * 5) % 50),   TransactionType.P2P_TRANSFER));
            }
            if (day % 4 == 1) {
                history.add(new HistEntry(day, 18, 15, saimon, osama,  BigDecimal.valueOf(50 + (day * 8) % 40),   TransactionType.P2P_TRANSFER));
            }
            if (day % 5 == 2) {
                history.add(new HistEntry(day, 18, 30, sakib,  saimon, BigDecimal.valueOf(60 + (day * 6) % 60),   TransactionType.P2P_TRANSFER));
            }
            if (day % 6 == 3) {
                history.add(new HistEntry(day, 18, 45, saimon, sakib,  BigDecimal.valueOf(45 + (day * 9) % 45),   TransactionType.P2P_TRANSFER));
            }
            if (day % 5 == 4) {
                history.add(new HistEntry(day, 19, 00, osama,  sakib,  BigDecimal.valueOf(55 + (day * 4) % 45),   TransactionType.P2P_TRANSFER));
            }
            if (day % 7 == 2) {
                history.add(new HistEntry(day, 19, 15, rezaul, sakib,  new BigDecimal("50.00"),                    TransactionType.P2P_TRANSFER));
            }
            if (day % 7 == 5) {
                history.add(new HistEntry(day, 19, 30, sakib,  rezaul, new BigDecimal("60.00"),                    TransactionType.P2P_TRANSFER));
            }
        }

        // Persist all historical transactions with backdated timestamps
        int histSeq = 1;
        for (HistEntry e : history) {
            Instant ts = today.minusDays(e.daysAgo())
                    .atTime(e.hour(), e.minute())
                    .toInstant(ZoneOffset.UTC);
            String txnId = "TXN-HIST-%04d".formatted(histSeq++);

            // Apply wallet deltas
            Wallet senderWallet  = wallets.findByUser_UserId(e.sender().getUserId()).orElseThrow();
            Wallet receiverWallet= wallets.findByUser_UserId(e.receiver().getUserId()).orElseThrow();

            // Debit sender, credit receiver
            senderWallet.debit(e.amount());
            receiverWallet.credit(e.amount());

            Transaction txn = new Transaction(txnId, e.sender(), e.receiver(), e.amount(), e.type());
            setTimestamp(txn, ts);
            transactions.save(txn);
        }

        log.info("""

                ---------------------------------------------------------------
                 UniPay demo campus seeded (password for all: demo1234)
                 STUDENT 0112330140 Saimon    | FACULTY 0111910667 Dr. Nafees
                 STUDENT 0112330378 Osama     | STAFF   UIU-STF-101 Rezaul
                 STUDENT 0112330586 Sakib     | VENDOR  V-CAFE-01 Canteen
                                              | VENDOR  V-BOOK-01 Book Shop
                                              | VENDOR  V-FOOD-01 Samosa Corner
                 Historical tx seeded: 30 days of full expense/earnings data for ALL roles
                 MFS sandbox OTP: 123456
                 bKash system user: BKASH_GATEWAY (auto-provisioned)
                ---------------------------------------------------------------""");
    }

    /**
     * Backdates a Transaction's timestamp using the package-private accessor.
     * We call via reflection since DataSeeder is in a different package.
     */
    private static void setTimestamp(Transaction txn, Instant ts) {
        txn.setTimestamp(ts);
    }

    /**
     * Ensures the {@code BKASH_GATEWAY} system account exists in the Users
     * table. It is a virtual VENDOR account used solely as the {@code sender}
     * FK on real bKash cash-in Transaction rows. It has no wallet, no login
     * capability (random password hash), and no vendor profile — it is purely
     * an FK anchor for the ledger.
     */
    private void ensureBkashGatewayUser() {
        if (users.existsById(BkashTokenizedService.BKASH_GATEWAY_USER_ID)) {
            return;
        }
        User gateway = new User(
                BkashTokenizedService.BKASH_GATEWAY_USER_ID,
                "bKash Payment Gateway",
                "00000000000",          // non-registrable sentinel phone
                passwordEncoder.encode(java.util.UUID.randomUUID().toString()), // unguessable
                Role.VENDOR);
        users.save(gateway);
        log.info("bKash gateway system user provisioned: {}", BkashTokenizedService.BKASH_GATEWAY_USER_ID);
    }
}
