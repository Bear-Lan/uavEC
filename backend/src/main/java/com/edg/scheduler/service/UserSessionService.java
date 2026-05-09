package com.edg.scheduler.service;

import com.edg.scheduler.repository.OperatorRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import org.redisson.api.RedissonClient;
import org.redisson.api.RSet;

/**
 * 追踪在线用户，提供 WebSocket 广播通知。
 * 使用 Redis Set 存储，支持多实例部署。
 */
@Slf4j
@Service
public class UserSessionService {

    private static final String ONLINE_USERS_KEY = "session:online_users";

    @Autowired
    private OperatorRepository operatorRepository;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private RedissonClient redissonClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        log.info("UserSessionService initialized with Redis");
    }

    /**
     * 用户上线：记录到 Redis 在线用户集合，并广播更新
     */
    public void userLogin(String username) {
        OnlineUserInfo info = new OnlineUserInfo();
        info.setUsername(username);
        info.setLoginTime(System.currentTimeMillis());

        operatorRepository.findByUsername(username).ifPresent(op -> {
            info.setX(op.getX());
            info.setY(op.getY());
        });

        RSet<String> onlineSet = redissonClient.getSet(ONLINE_USERS_KEY);

        // 移除旧记录
        onlineSet.removeIf(json -> {
            try {
                OnlineUserInfo existing = objectMapper.readValue(json, OnlineUserInfo.class);
                return existing.getUsername().equals(username);
            } catch (JsonProcessingException e) {
                return false;
            }
        });

        // 添加新记录
        try {
            onlineSet.add(objectMapper.writeValueAsString(info));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize OnlineUserInfo for user {}", username, e);
            return;
        }

        log.info("User {} logged in, broadcasting to /topic/users", username);
        broadcastOnlineUsers();
    }

    /**
     * 用户下线：从 Redis 在线用户集合移除，并广播更新
     */
    public void userLogout(String username) {
        RSet<String> onlineSet = redissonClient.getSet(ONLINE_USERS_KEY);
        onlineSet.removeIf(json -> {
            try {
                OnlineUserInfo existing = objectMapper.readValue(json, OnlineUserInfo.class);
                return existing.getUsername().equals(username);
            } catch (JsonProcessingException e) {
                return false;
            }
        });

        log.info("User {} logged out, broadcasting to /topic/users", username);
        broadcastOnlineUsers();
    }

    /**
     * 获取所有在线用户列表
     */
    public List<OnlineUserInfo> getOnlineUsers() {
        List<OnlineUserInfo> result = new ArrayList<>();
        RSet<String> onlineSet = redissonClient.getSet(ONLINE_USERS_KEY);

        for (String json : onlineSet) {
            try {
                result.add(objectMapper.readValue(json, OnlineUserInfo.class));
            } catch (JsonProcessingException e) {
                log.warn("Failed to parse OnlineUserInfo from Redis: {}", json, e);
            }
        }

        return result;
    }

    /**
     * 广播在线用户列表到 /topic/users
     */
    public void broadcastOnlineUsers() {
        List<OnlineUserInfo> users = getOnlineUsers();
        messagingTemplate.convertAndSend("/topic/users", users);
        log.debug("Broadcasting {} online users to /topic/users", users.size());
    }

    @lombok.Data
    public static class OnlineUserInfo {
        private String username;
        private double x;
        private double y;
        private long loginTime;
    }
}
