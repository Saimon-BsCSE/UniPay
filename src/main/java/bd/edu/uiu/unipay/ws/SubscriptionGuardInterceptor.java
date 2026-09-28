package bd.edu.uiu.unipay.ws;

import bd.edu.uiu.unipay.security.AppUserPrincipal;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * RBAC enforcement on the real-time channel: a subscriber may only listen to
 * {@code /topic/notifications/<own-id>} and, for vendors,
 * {@code /topic/vendor/<own-id>} — nobody can eavesdrop on another user's
 * payment alerts.
 */
@Component
public class SubscriptionGuardInterceptor implements ChannelInterceptor {

    private static final Pattern ALLOWED = Pattern.compile("^/topic/(notifications|vendor)/(.+)$");

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(
                message, StompHeaderAccessor.class);

        if (accessor != null && StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            if (!(accessor.getUser() instanceof AppUserPrincipal principal)) {
                throw new IllegalArgumentException("Subscription rejected: not authenticated.");
            }
            String destination = accessor.getDestination();
            var matcher = ALLOWED.matcher(destination == null ? "" : destination);
            if (destination == null || !matcher.matches()
                    || !principal.getName().equals(matcher.group(2))) {
                throw new IllegalArgumentException(
                        "Subscription rejected: you may only subscribe to your own topics.");
            }
        }
        return message;
    }
}
