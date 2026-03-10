package com.example.chat_backend.config;

import com.example.chat_backend.registry.UserSessionRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final String USER_DESTINATION_PREFIX = "/user/";
    private static final String USER_QUEUE_SUFFIX = "/queue/messages";

    private final UserSessionRegistry userSessionRegistry;

    /**
     * when client connects to /ws endpoint, it will be upgraded to WebSocket connection.
     *
     * @param registry
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")//allow CORS for testing.
                .withSockJS();// fall back to SockJs if WebSocket is not supported by client or blocked by proxy,
        // sockJs will use HTTP long polling to simulate WebSocket connection,
        // which is less efficient but more compatible.
    }

    /**
     * configure message broker (STOMP).
     *
     * @param registry
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // define inbound prefix, client's "SEND" message url match /app/** will route to Controller (@MessageMapping)
        registry.setApplicationDestinationPrefixes("/app");
        // define outbound prefix, if server sends message to client with url start with /user/** or /topic/**, it will be routed to broker and sent to client.
        registry.enableSimpleBroker("/user", "/topic");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // add interceptor to capture SUBSCRIBE message(can't use filter because it's not HTTP request)
        registration.interceptors(new ChannelInterceptor() {

            // preSend let us intercept the message before it's send to broker.
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor == null) {
                    return message;
                }

                if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
                    String destination = accessor.getDestination();
                    String sessionId = accessor.getSessionId();// each connection share the same sessionId until disconnected.
                    if (destination != null
                            && destination.startsWith(USER_DESTINATION_PREFIX)// ex: /user/{userId}/queue/messages.
                            && destination.endsWith(USER_QUEUE_SUFFIX)) {
                        String userId = destination.substring(// extract userId from url.
                                USER_DESTINATION_PREFIX.length(),
                                destination.length() - USER_QUEUE_SUFFIX.length());
                        userSessionRegistry.register(userId, sessionId); // register userId and sessionId in concurrentHashMap.
                    }
                }

                return message;
            }
        });
    }
}
