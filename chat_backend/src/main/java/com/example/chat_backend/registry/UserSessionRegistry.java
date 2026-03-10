package com.example.chat_backend.registry;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class UserSessionRegistry implements ApplicationListener<SessionDisconnectEvent> {

    // Bidirectional Index
    private final ConcurrentHashMap<String, String> userToSession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> sessionToUser = new ConcurrentHashMap<>();

    public void register(String userId, String sessionId) {
        userToSession.put(userId, sessionId);
        sessionToUser.put(sessionId, userId);
        log.info("[UserSessionRegistry][Register] Success. userId={}, sessionId={}", userId, sessionId);
    }

    public boolean isConnected(String userId) {
        return userToSession.containsKey(userId);
    }

    @Override
    public void onApplicationEvent(SessionDisconnectEvent event) { 
        String sessionId = event.getSessionId();
        String userId = sessionToUser.remove(sessionId);
        if (userId != null) {
            userToSession.remove(userId);
            log.info("[UserSessionRegistry][Deregister] Success. userId={}, sessionId={}", userId, sessionId);
        }
    }
}
