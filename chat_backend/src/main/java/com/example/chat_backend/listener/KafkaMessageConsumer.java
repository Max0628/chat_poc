package com.example.chat_backend.listener;

import com.example.chat_backend.constant.KafkaTopic;
import com.example.chat_backend.dto.ChatMessageDTO;
import com.example.chat_backend.registry.UserSessionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class KafkaMessageConsumer {

    private final UserSessionRegistry userSessionRegistry;
    private final SimpMessagingTemplate messagingTemplate;

    @KafkaListener(topics = KafkaTopic.CHAT_MESSAGES)
    public void consume(ChatMessageDTO message) {
        log.info("[Kafka-Consumer][Consume] Received. fromUserId={}, toUserId={}",
                message.getFromUserId(), message.getToUserId());
        long startTime = System.currentTimeMillis();

        if (!userSessionRegistry.isConnected(message.getToUserId())) {
            log.debug("[Kafka-Consumer][Consume] User not connected locally, skipping. toUserId={}",
                    message.getToUserId());
            return;
        }

        try {
            messagingTemplate.convertAndSend("/user/" + message.getToUserId() + "/queue/messages", message);
            log.info("[Kafka-Consumer][Consume] Delivered. toUserId={}, duration={}ms",
                    message.getToUserId(), System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            log.error("[Kafka-Consumer][Consume] Delivery failed! toUserId={}, duration={}ms, cause={}",
                    message.getToUserId(), System.currentTimeMillis() - startTime, e.getMessage(), e);
        }
    }
}
