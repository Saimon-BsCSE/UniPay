package bd.edu.uiu.unipay.notification;

import bd.edu.uiu.unipay.config.AsyncConfig;
import bd.edu.uiu.unipay.transaction.Transaction;
import bd.edu.uiu.unipay.transaction.TransactionRepository;
import bd.edu.uiu.unipay.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Pushes real-time STOMP events to browsers from the bounded background pool
 * ({@link AsyncConfig#EXECUTOR}) and persists notifications for the notification center.
 */
@Service
public class NotificationService {

    public static final String TOPIC_USER_NOTIFICATIONS = "/topic/notifications/";
    public static final String TOPIC_VENDOR_POS = "/topic/vendor/";

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final NotificationRepository notificationRepository;
    private final TransactionRepository transactionRepository;

    public NotificationService(SimpMessagingTemplate messagingTemplate,
                               NotificationRepository notificationRepository,
                               TransactionRepository transactionRepository) {
        this.messagingTemplate = messagingTemplate;
        this.notificationRepository = notificationRepository;
        this.transactionRepository = transactionRepository;
    }

    /** Live P2P alert: "You received ৳X from <sender>". */
    @Async(AsyncConfig.EXECUTOR)
    public void pushP2pReceived(User receiver, User sender, Transaction txn) {
        String msg = "%s sent you ৳%s".formatted(sender.getFullName(), txn.getAmount().toPlainString());
        saveNotification(receiver.getUserId(), "P2P_RECEIVED", "Money received", msg, txn.getAmount(), sender.getFullName(), txn.getTransactionId());

        WsEvent event = WsEvent.of("P2P_RECEIVED", "Money received", msg,
                txn.getAmount(), sender.getFullName(), txn.getTransactionId());
        messagingTemplate.convertAndSend(TOPIC_USER_NOTIFICATIONS + receiver.getUserId(), event);
        log.debug("Pushed P2P alert to {}", receiver.getUserId());
    }

    /** Vendor POS alert: payment banner + audio chime on the vendor dashboard. */
    @Async(AsyncConfig.EXECUTOR)
    public void pushVendorPaymentAlert(User vendor, User payer, Transaction txn) {
        String msg = "%s paid ৳%s".formatted(payer.getFullName(), txn.getAmount().toPlainString());
        saveNotification(vendor.getUserId(), "VENDOR_PAYMENT", "Payment received", msg, txn.getAmount(), payer.getFullName(), txn.getTransactionId());

        WsEvent event = WsEvent.of("VENDOR_PAYMENT", "Payment received", msg,
                txn.getAmount(), payer.getFullName(), txn.getTransactionId());
        messagingTemplate.convertAndSend(TOPIC_VENDOR_POS + vendor.getUserId(), event);
        log.debug("Pushed POS alert to vendor {}", vendor.getUserId());
    }

    /** Confirms a successful MFS cash-in to the topping-up user. */
    @Async(AsyncConfig.EXECUTOR)
    public void pushCashInCompleted(User user, Transaction txn) {
        String msg = "৳%s was added to your wallet".formatted(txn.getAmount().toPlainString());
        saveNotification(user.getUserId(), "CASH_IN_COMPLETED", "Cash-in successful", msg, txn.getAmount(), "MFS Gateway", txn.getTransactionId());

        WsEvent event = WsEvent.of("CASH_IN_COMPLETED", "Cash-in successful", msg,
                txn.getAmount(), "MFS Gateway", txn.getTransactionId());
        messagingTemplate.convertAndSend(TOPIC_USER_NOTIFICATIONS + user.getUserId(), event);
        log.debug("Pushed cash-in alert to {}", user.getUserId());
    }

    /** Live SplitPay request alert to participant: "<sender> requested ৳X for '<bill>'". */
    @Async(AsyncConfig.EXECUTOR)
    public void pushSplitRequest(User participant, User creator, String billTitle, java.math.BigDecimal shareAmount, String requestId) {
        String msg = "%s requested ৳%s for '%s'".formatted(creator.getFullName(), shareAmount.toPlainString(), billTitle);
        saveNotification(participant.getUserId(), "SPLIT_REQUEST", "SplitPay Request", msg, shareAmount, creator.getFullName(), requestId);

        WsEvent event = WsEvent.of("SPLIT_REQUEST", "SplitPay Request", msg,
                shareAmount, creator.getFullName(), requestId);
        messagingTemplate.convertAndSend(TOPIC_USER_NOTIFICATIONS + participant.getUserId(), event);
        log.debug("Pushed SplitPay request alert to {}", participant.getUserId());
    }

    /** Live alert to bill creator when participant accepts & pays their share. */
    @Async(AsyncConfig.EXECUTOR)
    public void pushSplitAccepted(User creator, User participant, String billTitle, java.math.BigDecimal shareAmount, String txnId) {
        String msg = "%s paid ৳%s for '%s'".formatted(participant.getFullName(), shareAmount.toPlainString(), billTitle);
        saveNotification(creator.getUserId(), "SPLIT_ACCEPTED", "Split Share Received", msg, shareAmount, participant.getFullName(), txnId);

        WsEvent event = WsEvent.of("SPLIT_ACCEPTED", "Split Share Received", msg,
                shareAmount, participant.getFullName(), txnId);
        messagingTemplate.convertAndSend(TOPIC_USER_NOTIFICATIONS + creator.getUserId(), event);
        log.debug("Pushed SplitPay accepted alert to creator {}", creator.getUserId());
    }

    /** Alert to bill creator when a participant declines the request. */
    @Async(AsyncConfig.EXECUTOR)
    public void pushSplitDeclined(User creator, User participant, String billTitle) {
        String msg = "%s declined the request for '%s'".formatted(participant.getFullName(), billTitle);
        saveNotification(creator.getUserId(), "SPLIT_DECLINED", "Split Request Declined", msg, java.math.BigDecimal.ZERO, participant.getFullName(), "");

        WsEvent event = WsEvent.of("SPLIT_DECLINED", "Split Request Declined", msg,
                java.math.BigDecimal.ZERO, participant.getFullName(), "");
        messagingTemplate.convertAndSend(TOPIC_USER_NOTIFICATIONS + creator.getUserId(), event);
        log.debug("Pushed SplitPay declined alert to creator {}", creator.getUserId());
    }

    /** Alert to bill creator when all participants have settled the bill. */
    @Async(AsyncConfig.EXECUTOR)
    public void pushSplitSettled(User creator, String billTitle, java.math.BigDecimal totalAmount) {
        String msg = "All participants paid for '%s' (৳%s total)".formatted(billTitle, totalAmount.toPlainString());
        saveNotification(creator.getUserId(), "SPLIT_SETTLED", "Bill Fully Settled! 🎉", msg, totalAmount, "UniPay SplitPay", "");

        WsEvent event = WsEvent.of("SPLIT_SETTLED", "Bill Fully Settled! 🎉", msg,
                totalAmount, "UniPay SplitPay", "");
        messagingTemplate.convertAndSend(TOPIC_USER_NOTIFICATIONS + creator.getUserId(), event);
        log.debug("Pushed SplitPay settled alert to creator {}", creator.getUserId());
    }

    /**
     * Real bKash cash-in confirmation: surfaces the bKash {@code trxID} so the
     * user can use it as payment proof.
     */
    @Async(AsyncConfig.EXECUTOR)
    public void pushBkashCashInCompleted(User user, Transaction txn, String bkashTrxID) {
        String msg = "৳%s added to your wallet via bKash (Ref: %s)"
                .formatted(txn.getAmount().toPlainString(), bkashTrxID);
        saveNotification(user.getUserId(), "BKASH_CASH_IN_COMPLETED", "bKash Cash-In Successful! ✅", msg, txn.getAmount(), "bKash Gateway", txn.getTransactionId());

        WsEvent event = WsEvent.of("BKASH_CASH_IN_COMPLETED", "bKash Cash-In Successful! ✅", msg,
                txn.getAmount(), "bKash Gateway", txn.getTransactionId());
        messagingTemplate.convertAndSend(TOPIC_USER_NOTIFICATIONS + user.getUserId(), event);
        log.debug("Pushed bKash cash-in alert to {} with trxID={}", user.getUserId(), bkashTrxID);
    }

    private void saveNotification(String userId, String type, String title, String message,
                                  java.math.BigDecimal amount, String senderName, String txnId) {
        try {
            Notification notification = new Notification(userId, type, title, message, amount, senderName, txnId);
            notificationRepository.save(notification);
        } catch (Exception e) {
            log.error("Failed to persist notification for {}: {}", userId, e.getMessage());
        }
    }

    /**
     * Retrieves all notifications for a user, backfilling from recent transactions if none exist.
     */
    @Transactional
    public NotificationDtos.NotificationSummary getNotifications(String userId) {
        List<Notification> list = notificationRepository.findByUserIdOrderByCreatedAtDesc(userId);

        if (list.isEmpty()) {
            // Seed notifications from recent transactions if available
            try {
                var txns = transactionRepository.findBySender_UserIdOrReceiver_UserIdOrderByTimestampDesc(
                        userId, userId, PageRequest.of(0, 10));
                for (Transaction t : txns) {
                    boolean isReceiver = userId.equals(t.getReceiver().getUserId());
                    String type = t.getTransactionType().name();
                    String title;
                    String msg;
                    if ("MFS_CASH_IN".equals(type)) {
                        title = "Cash-In Successful";
                        msg = "৳%s added to your wallet".formatted(t.getAmount().toPlainString());
                    } else if ("VENDOR_PAYMENT".equals(type)) {
                        if (isReceiver) {
                            title = "Payment Received";
                            msg = "%s paid ৳%s".formatted(t.getSender().getFullName(), t.getAmount().toPlainString());
                        } else {
                            title = "Payment to Vendor";
                            msg = "Paid ৳%s to %s".formatted(t.getAmount().toPlainString(), t.getReceiver().getFullName());
                        }
                    } else if ("P2P_TRANSFER".equals(type)) {
                        if (isReceiver) {
                            title = "Money Received";
                            msg = "%s sent you ৳%s".formatted(t.getSender().getFullName(), t.getAmount().toPlainString());
                        } else {
                            title = "Money Sent";
                            msg = "Sent ৳%s to %s".formatted(t.getAmount().toPlainString(), t.getReceiver().getFullName());
                        }
                    } else {
                        title = "Transaction Recorded";
                        msg = "Transaction of ৳%s processed".formatted(t.getAmount().toPlainString());
                    }
                    Notification n = new Notification(userId, type, title, msg, t.getAmount(),
                            isReceiver ? t.getSender().getFullName() : t.getReceiver().getFullName(),
                            t.getTransactionId(), t.getTimestamp());
                    notificationRepository.save(n);
                    list.add(n);
                }
            } catch (Exception e) {
                log.warn("Could not backfill notifications for {}: {}", userId, e.getMessage());
            }
        }

        long unreadCount = notificationRepository.countByUserIdAndIsReadFalse(userId);
        List<NotificationDtos.NotificationResponse> dtos = list.stream()
                .map(NotificationDtos.NotificationResponse::from)
                .toList();

        return new NotificationDtos.NotificationSummary(unreadCount, dtos);
    }

    @Transactional
    public void markAllAsRead(String userId) {
        notificationRepository.markAllAsReadForUser(userId);
    }

    @Transactional
    public void markAsRead(Long id, String userId) {
        notificationRepository.findById(id).ifPresent(n -> {
            if (userId.equals(n.getUserId())) {
                n.setRead(true);
                notificationRepository.save(n);
            }
        });
    }
}
