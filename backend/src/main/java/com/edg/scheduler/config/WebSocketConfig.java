package com.edg.scheduler.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket 配置
 *
 * 启用 STOMP 协议的 WebSocket 消息代理：
 * - /topic: 服务器推送消息的前缀（发布-订阅模式）
 * - /app: 客户端发送消息的前缀（点对点模式）
 * - /ws: STOMP 端点（使用 SockJS 兼容不支持 WebSocket 的浏览器）
 *
 * 用于实时状态推送：
 * - /topic/nodes - 节点状态变更
 * - /topic/tasks - 任务状态变更
 * - /topic/notifications - 系统通知
 * - /topic/users - 在线用户列表
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Prefix for messages sent from server to client
        config.enableSimpleBroker("/topic");
        // Prefix for messages sent from client to server
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Endpoint for client to connect to WebSocket
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*").withSockJS();
    }
}
