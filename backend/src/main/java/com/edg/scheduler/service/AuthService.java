package com.edg.scheduler.service;

import com.edg.scheduler.model.Operator;
import com.edg.scheduler.model.UserDTO;
import com.edg.scheduler.repository.OperatorRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

/**
 * 认证服务
 *
 * 核心职责：
 * - 用户注册：创建新用户，密码BCrypt加密
 * - 用户登录：验证凭证，生成认证令牌
 * - 管理员初始化：首次启动创建默认admin账户
 * - 个人资料更新：修改用户名和位置坐标
 *
 * 事务策略：
 * - register: @Transactional 写操作
 * - login: @Transactional(readOnly=true) 读操作
 * - initializeDefaultAdminIfNeeded: @Transactional 首次创建
 */
@Slf4j
@Service
public class AuthService {

    @Autowired
    private OperatorRepository operatorRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private UserSessionService userSessionService;

    /**
     * 用户注册
     * @param username 用户名
     * @param password 密码（会被 BCrypt 加密）
     * @param x 坐标 X
     * @param y 坐标 Y
     * @return 注册成功的用户 DTO
     * @throws IllegalArgumentException 用户名已存在
     */
    @Transactional
    public UserDTO register(String username, String password, double x, double y) {
        if (operatorRepository.findByUsername(username).isPresent()) {
            throw new IllegalArgumentException("Username already exists");
        }
        Operator operator = new Operator(username, passwordEncoder.encode(password), x, y);
        operatorRepository.save(operator);
        log.info("New user registered: {}", username);
        return UserDTO.fromEntity(operator);
    }

    /**
     * 用户登录
     * @param username 用户名
     * @param password 密码
     * @return 包含用户信息和 Token 的 Map
     * @throws IllegalArgumentException 用户名或密码错误
     * @throws IllegalStateException 账户已被禁用
     */
    @Transactional
    public Map<String, Object> login(String username, String password) {
        Operator operator = operatorRepository.findByUsername(username).orElse(null);
        if (operator == null || !passwordEncoder.matches(password, operator.getPassword())) {
            throw new IllegalArgumentException("Invalid username or password");
        }
        if (!operator.isEnabled()) {
            throw new IllegalStateException("Account has been disabled");
        }

        String token = java.util.UUID.randomUUID().toString();
        operator.setToken(token);
        operator.setTokenExpiryTime(System.currentTimeMillis() + 24 * 60 * 60 * 1000);
        operator.setLastLoginTime(System.currentTimeMillis());
        operatorRepository.save(operator);

        userSessionService.userLogin(username);

        log.info("User {} logged in", username);
        return Map.of("user", UserDTO.fromEntity(operator), "token", token);
    }

    /**
     * 获取当前用户信息
     * @param username 用户名
     * @return 用户 DTO（如果存在）
     */
    @Transactional(readOnly = true)
    public Optional<UserDTO> getCurrentUser(String username) {
        return operatorRepository.findByUsername(username).map(UserDTO::fromEntity);
    }

    /**
     * 更新个人资料
     * @param currentUsername 当前用户名
     * @param newUsername 新用户名
     * @param x 坐标 X
     * @param y 坐标 Y
     * @return 更新后的用户 DTO
     * @throws IllegalArgumentException 当前用户不存在或新用户名已被占用
     */
    @Transactional
    public UserDTO updateProfile(String currentUsername, String newUsername, double x, double y) {
        Operator existing = operatorRepository.findByUsername(currentUsername)
                .orElseThrow(() -> new IllegalArgumentException("Current user not found"));

        if (!currentUsername.equals(newUsername)) {
            if (operatorRepository.findByUsername(newUsername).isPresent()) {
                throw new IllegalArgumentException("Username already exists");
            }
        }

        existing.setUsername(newUsername);
        existing.setX(x);
        existing.setY(y);
        operatorRepository.save(existing);
        log.info("Profile updated for user: {}", currentUsername);
        return UserDTO.fromEntity(existing);
    }

    /**
     * 初始化默认管理员账户（如果不存在）
     * @return 默认密码（如果创建了新管理员），否则返回 null
     */
    @Transactional
    public String initializeDefaultAdminIfNeeded() {
        if (operatorRepository.count() == 0) {
            String defaultPassword = java.util.UUID.randomUUID().toString().substring(0, 8);
            Operator admin = new Operator("admin", passwordEncoder.encode(defaultPassword), 50, 50);
            admin.setRole("ADMIN");
            operatorRepository.save(admin);
            log.info("Default admin user initialized");
            return defaultPassword;
        }
        return null;
    }
}