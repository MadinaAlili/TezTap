package com.teztap.controller.websocket;

import com.teztap.security.JwtUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtUtils jwtUtils;

    @Autowired
    public WebSocketConfig(JwtUtils jwtUtils) {
        this.jwtUtils = jwtUtils;
    }

    // SimpleBrokerMessageHandler requires a TaskScheduler when heartbeats are configured.
    // We create a dedicated one here rather than reusing MatchingService's scheduler
    // to keep WebSocket heartbeat timing isolated from delivery timeout scheduling.
    private TaskScheduler heartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.initialize();
        return scheduler;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic", "/queue")
              // Heartbeat: server sends every 10s, expects client to send every 10s.
              // Mobile clients that background silently are detected and disconnected
              // within ~20s instead of holding zombie connections open indefinitely.
              .setHeartbeatValue(new long[]{10000, 10000})
              .setTaskScheduler(heartbeatScheduler());

        config.setApplicationDestinationPrefixes("/app");
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/api")
                .setAllowedOriginPatterns("*");
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(64 * 1024)
                    .setSendBufferSizeLimit(512 * 1024)
                    .setSendTimeLimit(10 * 1000);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor =
                        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

                if (accessor == null) return message;

                StompCommand command = accessor.getCommand();

                if (StompCommand.CONNECT.equals(command)) {
                    String authHeader = accessor.getFirstNativeHeader("Authorization");

                    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                        throw new IllegalArgumentException("Missing or invalid Authorization header");
                    }

                    String jwt = authHeader.substring(7);

                    if (!jwtUtils.validateToken(jwt)) {
                        throw new IllegalArgumentException("Invalid or expired JWT token");
                    }

                    try {
                        var userDetails = jwtUtils.getUserDetailsFromToken(jwt);
                        Authentication auth = new UsernamePasswordAuthenticationToken(
                                userDetails, null, userDetails.getAuthorities());
                        accessor.setUser(auth);
                        System.err.println("[WebSocketConfig] CONNECT authenticated: " + userDetails.getUsername());
                    } catch (Exception e) {
                        throw new IllegalArgumentException("Could not authenticate WebSocket user: " + e.getMessage());
                    }

                } else if (StompCommand.SEND.equals(command)) {
                    if (accessor.getUser() == null) {
                        System.err.println("[WebSocketConfig] WARN: SEND to '" + accessor.getDestination()
                                + "' with null user — check CONNECT frame JWT.");
                    }

                } else if (StompCommand.DISCONNECT.equals(command)) {
                    java.security.Principal user = accessor.getUser();
                    System.err.println("[WebSocketConfig] DISCONNECT: " +
                            (user != null ? user.getName() : "unknown"));
                }

                return message;
            }
        });
    }
}
