package bd.edu.uiu.unipay.ws;

import bd.edu.uiu.unipay.security.AppUserDetailsService;
import bd.edu.uiu.unipay.security.AppUserPrincipal;
import bd.edu.uiu.unipay.security.JwtService;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.security.Principal;

/**
 * Authenticates the STOMP session at CONNECT time using the same JWT as the
 * REST API. The verified identity becomes the STOMP principal that
 * {@link SubscriptionGuardInterceptor} later checks on every SUBSCRIBE.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private final JwtService jwtService;
    private final AppUserDetailsService userDetailsService;

    public StompAuthChannelInterceptor(JwtService jwtService, AppUserDetailsService userDetailsService) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(
                message, StompHeaderAccessor.class);

        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            String token = firstNonBlank(accessor.getFirstNativeHeader("Authorization"));
            if (token != null && token.startsWith("Bearer ")) {
                token = token.substring(7);
            }
            if (token == null) {
                token = (String) accessor.getSessionAttributes().getOrDefault("token", null);
            }
            if (token == null || token.isBlank()) {
                throw new IllegalArgumentException("WebSocket connection rejected: missing JWT.");
            }

            Principal principal = jwtService.verifyToken(token)
                    .map(claims -> claims.getSubject())
                    .map(userDetailsService::loadUserByUsername)
                    .map(AppUserPrincipal.class::cast)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "WebSocket connection rejected: invalid or expired JWT."));

            accessor.setUser(principal);
        }
        return message;
    }

    private String firstNonBlank(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
