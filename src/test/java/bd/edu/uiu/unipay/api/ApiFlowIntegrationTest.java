package bd.edu.uiu.unipay.api;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end API integration test (H2, real security filter chain):
 * register → login → RBAC guards → MFS cash-in → P2P → vendor QR payment →
 * idempotency → ledger history.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiFlowIntegrationTest {

    @Autowired
    private MockMvc mvc;

    private static final String STUDENT = "TSTU" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    private static final String VENDOR = "TVEN" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    private static final String STUDENT_PHONE = "0179" + String.format("%07d", Math.abs(UUID.randomUUID().hashCode()) % 10_000_000);
    private static final String VENDOR_PHONE = "0189" + String.format("%07d", Math.abs(UUID.randomUUID().hashCode()) % 10_000_000);

    private static String studentToken;
    private static String vendorToken;

    private static final String SEED_STUDENT_2 = "0112330378"; // Osama, ৳1200 seeded

    @Test
    @Order(1)
    void registerStudentAndVendor() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                { "userId": "%s", "fullName": "Test Student", "phoneNumber": "%s",
                                  "password": "secret1", "role": "STUDENT" }
                                """.formatted(STUDENT, STUDENT_PHONE)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.role").value("STUDENT"))
                .andReturn();
        studentToken = JsonPath.read(result.getResponse().getContentAsString(), "$.token");

        result = mvc.perform(post("/api/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                { "userId": "%s", "fullName": "Test Canteen", "phoneNumber": "%s",
                                  "password": "secret1", "role": "VENDOR",
                                  "stallName": "Test Canteen", "stallCategory": "CANTEEN" }
                                """.formatted(VENDOR, VENDOR_PHONE)))
                .andExpect(status().isCreated())
                .andReturn();
        vendorToken = JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    @Test
    @Order(2)
    void duplicateRegistrationIs409() throws Exception {
        mvc.perform(post("/api/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                { "userId": "%s", "fullName": "Dup", "phoneNumber": "01555555555",
                                  "password": "secret1", "role": "STUDENT" }
                                """.formatted(STUDENT)))
                .andExpect(status().isConflict());
    }

    @Test
    @Order(3)
    void rbacGuards() throws Exception {
        // unauthenticated
        mvc.perform(get("/api/wallet")).andExpect(status().isUnauthorized());
        // STUDENT may not touch vendor POS endpoints
        mvc.perform(get("/api/vendor/profile")
                        .header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isForbidden());
        // VENDOR may read their profile
        mvc.perform(get("/api/vendor/profile")
                        .header("Authorization", "Bearer " + vendorToken))
                .andExpect(status().isOk());
    }

    @Test
    @Order(4)
    void cashInViaMfsSandbox() throws Exception {
        mvc.perform(post("/api/mfs/cash-in")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                { "provider": "BKASH", "amount": 500.00,
                                  "mfsPhoneNumber": "%s", "otp": "123456", "nonce": "%s" }
                                """.formatted(STUDENT_PHONE, UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payerNewBalance").value(500.0));

        // wrong OTP → rejected, balance untouched
        mvc.perform(post("/api/mfs/cash-in")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                { "provider": "NAGAD", "amount": 100.00,
                                  "mfsPhoneNumber": "%s", "otp": "000000", "nonce": "%s" }
                                """.formatted(STUDENT_PHONE, UUID.randomUUID())))
                .andExpect(status().is(402));

        mvc.perform(get("/api/wallet").header("Authorization", "Bearer " + studentToken))
                .andExpect(jsonPath("$.balance").value(500.0));
    }

    @Test
    @Order(5)
    void p2pTransferAndItsFailureModes() throws Exception {
        String nonce = UUID.randomUUID().toString();

        // happy path → seeded student receives 150
        mvc.perform(post("/api/payments/p2p")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                { "recipient": "%s", "amount": 150.00, "nonce": "%s" }
                                """.formatted(SEED_STUDENT_2, nonce)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payerNewBalance").value(350.0))
                .andExpect(jsonPath("$.type").value("P2P_TRANSFER"));

        // replaying the exact same nonce is idempotent — no double debit
        mvc.perform(post("/api/payments/p2p")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                { "recipient": "%s", "amount": 150.00, "nonce": "%s" }
                                """.formatted(SEED_STUDENT_2, nonce)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true));

        // insufficient funds (balance is exactly 350 at this point)
        mvc.perform(post("/api/payments/p2p")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                { "recipient": "%s", "amount": 350.01, "nonce": "%s" }
                                """.formatted(SEED_STUDENT_2, UUID.randomUUID())))
                .andExpect(status().isConflict());

        // unknown recipient
        mvc.perform(post("/api/payments/p2p")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                { "recipient": "GHOST000", "amount": 10.00, "nonce": "%s" }
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isNotFound());

        // amount validation
        mvc.perform(post("/api/payments/p2p")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                { "recipient": "%s", "amount": 0.50, "nonce": "%s" }
                                """.formatted(SEED_STUDENT_2, UUID.randomUUID())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(6)
    void vendorQrPaymentFlowWithDynamicQr() throws Exception {
        // vendor generates a dynamic QR with a preset amount of 49.50
        MvcResult qr = mvc.perform(get("/api/vendor/qr")
                        .queryParam("type", "DYNAMIC")
                        .queryParam("amount", "49.50")
                        .header("Authorization", "Bearer " + vendorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("DYNAMIC"))
                .andReturn();
        String payload = JsonPath.read(qr.getResponse().getContentAsString(), "$.payload");
        assertThat(payload).contains("UNIPAY:VENDOR:" + VENDOR).contains("NONCE");

        // wrong amount against the preset is refused
        mvc.perform(post("/api/payments/vendor")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                { "payload": "%s", "amount": 10.00, "nonce": "%s" }
                                """.formatted(payload, UUID.randomUUID())))
                .andExpect(status().isBadRequest());

        // exact amount succeeds: 350 − 49.50 = 300.50
        mvc.perform(post("/api/payments/vendor")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                { "payload": "%s", "amount": 49.50, "nonce": "%s" }
                                """.formatted(payload, UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payerNewBalance").value(300.5))
                .andExpect(jsonPath("$.type").value("VENDOR_PAYMENT"));

        // one-time nonce already consumed → replay rejected
        mvc.perform(post("/api/payments/vendor")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                { "payload": "%s", "amount": 49.50, "nonce": "%s" }
                                """.formatted(payload, UUID.randomUUID())))
                .andExpect(status().isConflict());

        // QR PNG renders
        mvc.perform(get("/api/vendor/qr.png")
                        .header("Authorization", "Bearer " + vendorToken))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(result -> assertThat(result.getResponse().getContentAsByteArray().length).isGreaterThan(200));

        // vendor daily stats reflect the sale
        mvc.perform(get("/api/vendor/stats")
                        .header("Authorization", "Bearer " + vendorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.todaySales").value(49.5))
                .andExpect(jsonPath("$.todayCount").value(1));

        // vendor can update stall profile
        mvc.perform(put("/api/vendor/profile")
                        .header("Authorization", "Bearer " + vendorToken)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                { "stallName": "Renamed Canteen", "stallCategory": "OTHER" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stallName").value("Renamed Canteen"));
    }

    @Test
    @Order(7)
    void ledgerHistoryReflectsEveryOperation() throws Exception {
        MvcResult result = mvc.perform(get("/api/wallet/transactions")
                        .header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("MFS_CASH_IN");
        assertThat(body).contains("P2P_TRANSFER");
        assertThat(body).contains("VENDOR_PAYMENT");
    }

    @Test
    @Order(8)
    void expenseTrackerCoversAllRolesForFullMonth() throws Exception {
        // Test all roles have 30 days of data and valid summaries
        String[] demoUsers = {
            "0112330140",  // STUDENT (Saimon)
            "0111910667",  // FACULTY (Dr. Nafees)
            "UIU-STF-101", // STAFF (Rezaul)
            "V-CAFE-01"   // VENDOR (UIU Central Canteen)
        };

        for (String uid : demoUsers) {
            MvcResult login = mvc.perform(post("/api/auth/login")
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    { "userId": "%s", "password": "demo1234" }
                                    """.formatted(uid)))
                    .andExpect(status().isOk())
                    .andReturn();
            String token = JsonPath.read(login.getResponse().getContentAsString(), "$.token");

            // 1. MONTHLY period: 30 points
            mvc.perform(get("/api/wallet/expense-summary?period=MONTHLY")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.period").value("MONTHLY"))
                    .andExpect(jsonPath("$.points.length()").value(30))
                    .andExpect(jsonPath("$.total").isNotEmpty());

            // 2. WEEKLY period: 7 points
            mvc.perform(get("/api/wallet/expense-summary?period=WEEKLY")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.period").value("WEEKLY"))
                    .andExpect(jsonPath("$.points.length()").value(7));

            // 3. DAILY period: 1 point
            mvc.perform(get("/api/wallet/expense-summary?period=DAILY")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.period").value("DAILY"))
                    .andExpect(jsonPath("$.points.length()").value(1));
        }
    }
}

