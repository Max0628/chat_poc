package com.example.chat_backend.adapter;

import com.example.chat_backend.constant.KafkaTopic;
import com.example.chat_backend.dto.ChatMessageDTO;
import com.example.chat_backend.service.MessagePublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class KafkaMessagePublisher implements MessagePublisher {

    private final KafkaTemplate<String, ChatMessageDTO> kafkaTemplate;

    @Override
    public void publish(ChatMessageDTO message) {
        log.info("[Kafka-Publisher][Publish] Start. fromUserId={}, toUserId={}, topic={}",
                message.getFromUserId(), message.getToUserId(), KafkaTopic.CHAT_MESSAGES);
        long startTime = System.currentTimeMillis();

        try {
            kafkaTemplate.send(KafkaTopic.CHAT_MESSAGES, message.getToUserId(), message);
            log.info("[Kafka-Publisher][Publish] Success. fromUserId={}, toUserId={}, duration={}ms",
                    message.getFromUserId(), message.getToUserId(), System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            log.warn("[Kafka-Publisher][Publish] Failed, will retry. fromUserId={}, toUserId={}, duration={}ms, cause={}",
                    message.getFromUserId(), message.getToUserId(), System.currentTimeMillis() - startTime, e.getMessage());
            throw e;
        }
    }
}
