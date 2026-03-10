package com.example.chat_backend.service;

import com.example.chat_backend.dto.ChatMessageDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatMessageService {

    private final MessagePublisher messagePublisher;

    public void sendMessage(ChatMessageDTO message) {
        log.info("[Chat-Service][Send] Start. fromUserId={}, toUserId={}", message.getFromUserId(), message.getToUserId());
        long startTime = System.currentTimeMillis();

        try {
            messagePublisher.publish(message);
            log.info("[Chat-Service][Send] Success. fromUserId={}, toUserId={}, duration={}ms",
                    message.getFromUserId(), message.getToUserId(), System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            log.error("[Chat-Service][Send] Failed! fromUserId={}, toUserId={}, duration={}ms, cause={}",
                    message.getFromUserId(), message.getToUserId(), System.currentTimeMillis() - startTime, e.getMessage(), e);
            throw e;
        }
    }
}
