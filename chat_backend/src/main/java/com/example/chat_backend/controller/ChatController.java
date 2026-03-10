package com.example.chat_backend.controller;

import com.example.chat_backend.dto.ChatMessageDTO;
import com.example.chat_backend.registry.UserSessionRegistry;
import com.example.chat_backend.service.ChatMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

@Controller
@RequiredArgsConstructor
@Slf4j
public class ChatController {

    private final ChatMessageService chatMessageService;
    private final UserSessionRegistry userSessionRegistry;

    @MessageMapping("/chat.send")
    public void sendMessage(ChatMessageDTO message, SimpMessageHeaderAccessor headerAccessor) {
        log.info("[Chat-Controller][Send] Received. fromUserId={}, toUserId={}",
                message.getFromUserId(), message.getToUserId());
        chatMessageService.sendMessage(message);
    }
}
