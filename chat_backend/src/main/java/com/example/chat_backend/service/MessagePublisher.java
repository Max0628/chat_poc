package com.example.chat_backend.service;

import com.example.chat_backend.dto.ChatMessageDTO;

public interface MessagePublisher {

    void publish(ChatMessageDTO message);
}
