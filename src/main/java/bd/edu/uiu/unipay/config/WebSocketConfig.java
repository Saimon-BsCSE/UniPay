package bd.edu.uiu.unipay.config;

import bd.edu.uiu.unipay.ws.SubscriptionGuardInterceptor;
import bd.edu.uiu.unipay.ws.StompAuthChannelInterceptor;
import bd.edu.uiu.unipay.ws.TokenHandshakeInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over SockJS real-time channel — mandatory networking requirement.
 *
 * <ul>
 *   <li>{@code /topic/notifications/{userId}} — live P2P "money received" alerts</li>
 *   <li>{@code /topic/vendor/{vendorId}}      — vendor POS payment banners + audio chime</li>
 * </ul>
 *
 * Clients authenticate at STOMP CONNECT with their JWT; the
 * {@link SubscriptionGuardInterceptor} prevents a user from subscribing to
 * somebody else's topic.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;
    private final SubscriptionGuardInterceptor subscriptionGuardInterceptor;
    private final TokenHandshakeInterceptor tokenHandshakeInterceptor;

    public WebSocketConfig(StompAuthChannelInterceptor stompAuthChannelInterceptor,
                           SubscriptionGuardInterceptor subscriptionGuardInterceptor,
                           TokenHandshakeInterceptor tokenHandshakeInterceptor) {
        this.stompAuthChannelInterceptor = stompAuthChannelInterceptor;
        this.subscriptionGuardInterceptor = subscriptionGuardInterceptor;
        this.tokenHandshakeInterceptor = tokenHandshakeInterceptor;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .addInterceptors(tokenHandshakeInterceptor)
                .withSockJS(); // falls back to xhr-streaming/polling when WebSocket is blocked
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthChannelInterceptor, subscriptionGuardInterceptor);
    }
}
