package bd.edu.uiu.unipay.notification;

import java.math.BigDecimal;
import java.time.Instant;

public final class NotificationDtos {

    private NotificationDtos() {}

    public record NotificationResponse(
            Long id,
            String type,
            String title,
            String message,
            BigDecimal amount,
            String senderName,
            String transactionId,
            boolean isRead,
            Instant createdAt,
            String timeAgo
    ) {
        public static NotificationResponse from(Notification n) {
            return new NotificationResponse(
                    n.getId(),
                    n.getType(),
                    n.getTitle(),
                    n.getMessage(),
                    n.getAmount(),
                    n.getSenderName(),
                    n.getTransactionId(),
                    n.isRead(),
                    n.getCreatedAt(),
                    formatTimeAgo(n.getCreatedAt())
            );
        }

        private static String formatTimeAgo(Instant instant) {
            if (instant == null) return "Just now";
            long seconds = Math.max(0, Instant.now().getEpochSecond() - instant.getEpochSecond());
            if (seconds < 60) return "Just now";
            long minutes = seconds / 60;
            if (minutes < 60) return minutes + "m ago";
            long hours = minutes / 60;
            if (hours < 24) return hours + "h ago";
            long days = hours / 24;
            if (days < 7) return days + "d ago";
            return (days / 7) + "w ago";
        }
    }

    public record NotificationSummary(
            long unreadCount,
            java.util.List<NotificationResponse> notifications
    ) {}
}
