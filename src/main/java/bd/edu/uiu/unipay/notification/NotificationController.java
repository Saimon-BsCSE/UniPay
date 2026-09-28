package bd.edu.uiu.unipay.notification;

import bd.edu.uiu.unipay.security.AppUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "Notifications", description = "User notification center with history and read state")
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Operation(summary = "Get user notifications and unread count")
    @GetMapping
    public NotificationDtos.NotificationSummary getNotifications(@AuthenticationPrincipal AppUserPrincipal principal) {
        return notificationService.getNotifications(principal.getId());
    }

    @Operation(summary = "Mark all notifications as read")
    @PostMapping("/read-all")
    public ResponseEntity<?> markAllAsRead(@AuthenticationPrincipal AppUserPrincipal principal) {
        notificationService.markAllAsRead(principal.getId());
        return ResponseEntity.ok(Map.of("success", true, "message", "All notifications marked as read"));
    }

    @Operation(summary = "Mark a single notification as read")
    @PostMapping("/{id}/read")
    public ResponseEntity<?> markAsRead(@AuthenticationPrincipal AppUserPrincipal principal,
                                        @PathVariable Long id) {
        notificationService.markAsRead(id, principal.getId());
        return ResponseEntity.ok(Map.of("success", true));
    }
}
