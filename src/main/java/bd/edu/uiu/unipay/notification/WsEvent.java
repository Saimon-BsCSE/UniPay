package bd.edu.uiu.unipay.notification;

import java.math.BigDecimal;

/**
 * JSON payload pushed over STOMP to browsers. Consumed by the SPA to render
 * live banners, toasts and audio chimes.
 */
public record WsEvent(String type,
                      String title,
                      String message,
                      BigDecimal amount,
                      String senderName,
                      String transactionId,
                      String timestamp) {

    public static WsEvent of(String type, String title, String message, BigDecimal amount,
                             String senderName, String transactionId) {
        return new WsEvent(type, title, message, amount, senderName, transactionId,
                java.time.Instant.now().toString());
    }
}
