package com.example.chat_backend.dto;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
public class ChatMessageDTO {

    String fromUserId;
    String toUserId;
    String content;
    String timestamp;
    String groupId;
}
