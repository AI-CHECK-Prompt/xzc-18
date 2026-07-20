package com.icu.monitor.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.icu.monitor.domain.AlertEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 护士站推送端：WebSocket。
 * 端到端延迟目标：< 3s。
 */
@Component
public class NurseStationPusher extends TextWebSocketHandler {
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    @Override public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        try { session.sendMessage(new TextMessage("{\"type\":\"hello\",\"ts\":\"" + java.time.OffsetDateTime.now() + "\"}")); } catch (Exception ignore) {}
    }

    @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    public void push(AlertEvent a) {
        if (sessions.isEmpty()) return;
        try {
            String msg = json.writeValueAsString(a);
            TextMessage tm = new TextMessage(msg);
            for (WebSocketSession s : sessions) {
                try { s.sendMessage(tm); } catch (Exception ignore) {}
            }
        } catch (Exception ignore) {}
    }

    public int sessionCount() { return sessions.size(); }
}
