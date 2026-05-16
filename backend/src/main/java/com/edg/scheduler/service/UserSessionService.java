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
 * 用户会话服务
 *
 * 核心职责：
 * - 追踪在线用户（基于 Redis Set 存储，支持多实例部署）
 * - 用户上线/下线广播
 * - WebSocket 实时推送在线用户列表
 *
 * 使用 Redis Set 存储在线用户信息，支持水平扩展
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

    /**
     * 初始化用户会话服务
     *
     * 功能描述：
     * - @PostConstruct生命周期钩子
     * - 在Spring容器初始化完成后执行
     * - 用于确认Redis连接就绪
     */
    @PostConstruct
    public void init() {
        log.info("UserSessionService 初始化完成，Redis连接就绪");
    }

    /**
     * 用户登录上线
     *
     * 功能描述：
     * - 创建用户在线信息（包含用户名、登录时间、坐标）
     * - 从数据库获取用户的坐标信息
     * - 在Redis中移除该用户的旧记录（如果存在）
     * - 将新记录添加到Redis在线用户集合
     * - 广播更新后的在线用户列表到WebSocket
     *
     * 存储格式：Redis Set，元素为JSON序列化的OnlineUserInfo
     *
     * @param username 用户名
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
            log.error("序列化OnlineUserInfo失败: user={}", username, e);
            return;
        }

        log.info("用户 {} 登录上线，广播到 /topic/users", username);
        broadcastOnlineUsers();
    }

    /**
     * 用户登出下线
     *
     * 功能描述：
     * - 从Redis在线用户集合中移除该用户
     * - 广播更新后的在线用户列表到WebSocket
     *
     * @param username 用户名
     */
    public void userLogout(String username) {
        RSet<String> onlineSet = redissonClient.getSet(ONLINE_USERS_KEY);

        onlineSet.removeIf(json -> {
            try {
                OnlineUserInfo existing = objectMapper.readValue(json, OnlineUserInfo.class);
                return existing.getUsername().equals(username);
            } catch (Exception e) {
                log.error("登出时解析OnlineUserInfo失败: json={}", json, e);
                return false;
            }
        });

        log.info("User {} logged out, broadcasting to /topic/users", username);
        broadcastOnlineUsers();
    }

    /**
     * 获取所有在线用户列表
     *
     * @return 在线用户信息列表
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
     * 广播在线用户列表
     *
     * 功能描述：
     * - 获取当前所有在线用户
     * - 推送到WebSocket的/topic/users主题
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
