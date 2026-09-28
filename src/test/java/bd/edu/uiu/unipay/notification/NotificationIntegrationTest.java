package bd.edu.uiu.unipay.notification;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
class NotificationIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private NotificationRepository notificationRepository;

    @Test
    void unauthenticatedRequestRejected() throws Exception {
        mvc.perform(get("/api/notifications"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getNotificationsAndMarkAsRead() throws Exception {
        // Log in as seeded student
        MvcResult loginResult = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "userId": "0112330140", "password": "demo1234" }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        String token = JsonPath.read(loginResult.getResponse().getContentAsString(), "$.token");

        // Seed a test notification
        Notification n = new Notification("0112330140", "P2P_RECEIVED", "Money received",
                "Osama sent you ৳50.00", new BigDecimal("50.00"), "Osama", "TXN-TEST-123");
        notificationRepository.save(n);

        // Fetch notifications
        mvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notifications").isArray())
                .andExpect(jsonPath("$.notifications[0].title").exists())
                .andExpect(jsonPath("$.notifications[0].createdAt").exists());

        // Mark all as read
        mvc.perform(post("/api/notifications/read-all")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
